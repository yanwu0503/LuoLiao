package com.luoliaoo.wechat.data

import com.luoliaoo.wechat.core.AppContextHolder
import com.luoliaoo.wechat.core.Log
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 使用人数上报（匿名计数）。
 *
 * 用途：让官网能显示「当前有多少人在用」与「历史累计有多少人用过」。
 *
 * 隐私边界 —— 这是本文件最重要的约定：
 *   · 只上报「有人在使用」这一事实。不上报聊天内容、昵称、联系人、AI 密钥、任何 ID。
 *   · 客户端 IP 由服务端从 TCP 连接读取，**模块不发送、也无从知道**自己的公网 IP。
 *     （模块无法得知运营商 NAT 后的公网出口，所以「客户端 IP」只能在服务端取。）
 *   · 不做设备指纹：不带 ANDROID_ID / IMEI / 序列号 / 安装 ID。
 *     去重完全由服务端按 IP 完成，因此同一 NAT 后的多台设备会被算成一个人（已知局限）。
 *
 * 节奏：每 60 秒一次。失败按 5s / 15s / 45s 退避重试，最多 3 次。
 *   重试上报的是**同一个时间窗**，服务端按分钟 + IP 去重，重复不会造成脏数据。
 *
 * 生命周期：随宿主进程启动，进程被杀即停止 —— 这正好让「当前人数」反映真实在线情况。
 */
object UsageReporter {

    private const val TAG = "LuoLiao"

    /** 上报间隔与地址都集中在 core/Constants.kt，这里只读不写。 */
    private val INTERVAL_SEC = com.luoliaoo.wechat.core.Constants.USAGE_INTERVAL_SEC

    /** 首次延迟：等宿主启动稳定后再发，避免和模块初始化抢资源。 */
    private const val INITIAL_DELAY_SEC = 12L

    /** 失败重试的退避表（秒）。用完就放弃这一轮，等下一个周期。 */
    private val RETRY_BACKOFF_SEC = longArrayOf(5, 15, 45)

    /**
     * 上报地址。取值定义在 core/Constants.kt 的 USAGE_ENDPOINT，
     * 「为什么当前必须是 http、以及明文流量的风险」也在那里统一说明 ——
     * 这里不再重复一遍，免得两处注释各说各话。
     */
    private val ENDPOINT = com.luoliaoo.wechat.core.Constants.USAGE_ENDPOINT

    /** 上报线程用单线程池：一次只有一个请求在飞，不会并发轰炸服务端。 */
    private var scheduler: ScheduledExecutorService? = null

    private val started = AtomicBoolean(false)

    /** 上一次成功上报的时间，仅用于日志与诊断。 */
    private val lastOkAt = AtomicLong(0L)

    /**
     * 服务端「最新版本号」的本地缓存（用户第 1 项要求）。
     *
     * 【为什么要缓存】用户要求：悬浮面板的「最新版本」那一栏要**读缓存**，
     * 而不是每次渲染都去联网查。理由很实在 ——
     *   · 悬浮面板每次打开、每次重绘都会走 render()，在里面发网络请求是灾难；
     *   · 而版本号几乎不会变，缓存几分钟完全够用。
     *
     * 【什么时候刷新】两个时机，都复用**已有的**上报请求，不新增网络目标：
     *   ① 模块首次加载：启动后 12 秒（INITIAL_DELAY_SEC）第一次上报时顺带解析；
     *   ② 之后每 60 秒（USAGE_INTERVAL_SEC）一次，跟随同一个心跳。
     *   也就是说「检查更新」与「人数统计」始终是同一个请求，服务端不多担负载。
     *
     * 【为什么用 AtomicReference 而不是 @Volatile String】
     * 写入发生在上报线程、读取发生在主线程（渲染悬浮面板）。AtomicReference
     * 保证读到的要么是旧值要么是新值，不会读到半个引用 —— 对 String 而言
     * `@Volatile` 也够，但用 Atomic 类型语义更直白，且这里是长驻进程的共享状态。
     *
     * 空串表示「还没取到过」，界面据此显示 `--`，而不是显示一个假的版本号。
     */
    private val latestCache = java.util.concurrent.atomic.AtomicReference("")

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            /* 跟随重定向交给下面的 followSameHost() 自己判断，所以这里关掉 OkHttp 的自动跟随。

               为什么不能简单开成 true：那会跟随**任何**地址的跳转，
               万一响应被劫持，上报就被引到第三方去了。
               为什么也不能像原来那样一律不跟随：真实发生过故障 ——
               服务端启用 https 后把 http 强制 301 到 https，
               而上报地址当时还是 http，于是每次上报都拿到 301 直接失败，
               统计恒为 0，日志里只有一行 "usage: HTTP 301"。
               折中方案：只跟随**同主机**的跳转（见 followSameHost），
               既扛得住 http→https 这类正常升级，又跟不到别的域名去。 */
            .followRedirects(false)
            .retryOnConnectionFailure(false) // 重试由下面的退避逻辑负责，避免双重重试
            .build()
    }

    /** 启动周期上报。重复调用无副作用（宿主里有多条安装路径）。 */
    fun start() {
        if (!com.luoliaoo.wechat.core.Constants.USAGE_ENABLED) {
            Log.i(TAG, "usage: disabled by USAGE_ENABLED, not starting")
            return
        }

        /* ⚠️ 只在主进程上报。
           宿主会开好几个进程（微信实测有主进程、:push、:appbrand0、:appbrand1），
           模块在这些进程里都会被加载，于是每一个都会启动上报器。
           后果有两个：
             ① 同一个用户被上报 4 次 —— 服务端按 IP 去重所以数字不会错，
                但请求量和耗电都是 4 倍；
             ② 更新弹窗会被弹 4 遍（虽然"每进程只弹一次"能压住大部分，
                但用户看到的是莫名其妙的重复弹窗，而且后台进程弹窗更容易出问题）。
           所以只让主进程干活，其它进程直接不启动。
           主进程的判定：进程名 == 宿主包名（没有 ':' 后缀）。
           拿不到进程名时按主进程处理 —— 宁可多报也不要完全不报。 */
        if (!isMainProcess()) {
            Log.i(TAG, "usage: not the main process, reporter not started here")
            return
        }

        if (!started.compareAndSet(false, true)) return

        val exec = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "LuoLiao-usage").apply { isDaemon = true }
        }
        scheduler = exec

        exec.scheduleWithFixedDelay(
            { runCatching { tick() }.onFailure { Log.w(TAG, "usage: tick failed: " + it.message) } },
            INITIAL_DELAY_SEC,
            INTERVAL_SEC,
            TimeUnit.SECONDS,
        )

        Log.i(TAG, "usage: reporter started (every " + INTERVAL_SEC + "s)")
    }

    /**
     * 当前进程是不是宿主的**主进程**。
     *
     * 判定依据：进程名不含 ':'。子进程的名字形如 "com.tencent.mm:push"。
     *
     * 取进程名走反射调 ActivityThread.currentProcessName()（隐藏 API，
     * 编译期不可见，所以必须用反射 —— 直接写 android.app.ActivityThread 编译不过）。
     * 拿不到时退回 ApplicationInfo.processName。
     *
     * 全部失败时返回 true（按主进程处理）：
     * 这个判断只用来「减少重复上报」，判错的代价是多几次请求；
     * 而错判成非主进程的代价是**完全没有统计**，后者严重得多。
     */
    private fun isMainProcess(): Boolean = try {
        var name: String? = null

        /* ① ActivityThread.currentProcessName() —— Android 9+ 的静态方法 */
        try {
            val at = Class.forName("android.app.ActivityThread")
            name = at.getDeclaredMethod("currentProcessName").invoke(null) as? String
        } catch (_: Throwable) {
            // 老版本没有这个方法，试下一条
        }

        /* ② ApplicationInfo.processName —— 公开字段，兜底 */
        if (name == null) {
            name = try {
                android.app.Application.getProcessName()
            } catch (_: Throwable) {
                null
            }
        }
        if (name == null) {
            name = try {
                AppContextHolder.get()?.applicationInfo?.processName
            } catch (_: Throwable) {
                null
            }
        }

        if (name == null) true else !name.contains(':')
    } catch (_: Throwable) {
        true
    }

    /** 停止上报（宿主进程退出时由系统回收，这里主要给测试用）。 */
    fun stop() {
        scheduler?.shutdownNow()
        scheduler = null
        started.set(false)
    }

    /** 当前是否在跑，供诊断页读取。 */
    fun isRunning(): Boolean = started.get()

    /**
     * 读取**缓存**的服务端最新版本号（用户第 1 项）。
     *
     * 由 60 秒一次的上报心跳顺带刷新（见 latestCache 的注释）。
     * **纯内存读取，不联网、不阻塞**，可以直接在界面渲染里调用。
     *
     * @return 服务端版本号；从未成功取到过时返回空串。
     */
    fun cachedLatest(): String = latestCache.get()

    /**
     * 服务端版本与本机版本**是否不一致**（用户第 7 项规则）。
     *
     * 【规则来源】用户明确说明：「版本更新检测是检测是不是与服务器同一个版本」，
     * 并且在我提示「服务端当前比本机旧也会判定为不一致」之后，
     * 依然确认「只要不一致就触发」。所以这里就是**字符串不相等**，仅此而已。
     *
     * 【这不是「比大小」】本方法里没有 compareTo、没有逐段拆分数字比较 ——
     * 用户要的就是「是不是同一个版本」，而不是「谁更新」。
     * 因此服务端版本比本机旧时同样返回 true，这是**刻意的**，不是疏漏。
     *
     * @param serverLatest 服务端版本号；为空时返回 false（没数据时不该谎报「有更新」）。
     */
    fun versionsDiffer(serverLatest: String): Boolean {
        val s = serverLatest.trim()
        if (s.isEmpty()) return false
        val cur = com.luoliaoo.wechat.BuildInfo.VERSION_NAME.trim()
        if (cur.isEmpty()) return false
        return s != cur
    }

    /** 上一次成功上报距今多少秒；从未成功过返回 -1。 */
    fun secondsSinceLastSuccess(): Long {
        val t = lastOkAt.get()
        if (t == 0L) return -1L
        return (System.currentTimeMillis() - t) / 1000
    }

    /**
     * 一次上报，含退避重试。
     * 单个 60 秒周期内最多发 1 + 3 = 4 次请求（正常情况只有 1 次）。
     */
    private fun tick() {
        val ts = System.currentTimeMillis()
        var attempt = 0
        while (true) {
            if (send(ts)) {
                lastOkAt.set(System.currentTimeMillis())
                return
            }
            val wait = RETRY_BACKOFF_SEC.getOrNull(attempt) ?: return
            attempt++
            try {
                Thread.sleep(wait * 1000L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }

    /** 发一次请求。返回是否成功（HTTP 2xx）。 */
    private fun send(ts: Long): Boolean = try {
        // 时间戳以服务端为准（服务端会忽略它并回显偏差），客户端时区/改机都不影响统计。
        val ver = com.luoliaoo.wechat.BuildInfo.VERSION_NAME
        val payload = JSONObject()
            .put("ts", ts)
            .put("v", ver)
            /* 宿主名（WECHAT / DOUYIN）—— 纯统计用，方便区分两个宿主的活跃度。
               HostApp.current 是个属性（不是函数），枚举名本身就是稳定的标识，
               name 比 displayName 更适合上报（不受界面文案变化影响）。 */
            .put("host", com.luoliaoo.wechat.core.HostApp.current.name)
            .toString()

        val req = Request.Builder()
            .url(ENDPOINT)
            .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("User-Agent", "LuoLiao/" + ver)
            .build()

        http.newCall(req).execute().use { resp ->
            /* 遇到跳转时自己判断要不要跟（OkHttp 的自动跟随已被关掉，原因见 http 的注释）。 */
            val location = followSameHost(resp)
            if (location != null) {
                if (lastOkAt.get() == 0L) {
                    Log.i(TAG, "usage: redirected " + resp.code + " -> " + location)
                }
                return sendTo(location)
            }

            val body = resp.body?.string().orEmpty()
            if (resp.isSuccessful) {
                // 只在首次成功时打一行日志，避免每分钟刷屏
                if (lastOkAt.get() == 0L) {
                    Log.i(TAG, "usage: first report ok -> " + body.take(120))
                }
                /* 顺带处理服务端捎回来的版本信息 ——
                   检查更新与统计共用这一个响应，不额外发请求。 */
                handleUpdate(body)
                true
            } else {
                Log.w(TAG, "usage: HTTP " + resp.code + " " + body.take(120))
                false
            }
        }
    } catch (t: Throwable) {
        // 网络异常是常态（用户可能离线），只记一行简短日志，绝不能影响宿主。
        Log.w(TAG, "usage: send failed: " + (t.message ?: t.javaClass.simpleName))
        false
    }

    /**
     * 解析响应里的 update 字段，需要时弹更新提示。
     *
     * 关于「为什么在这里弹窗」：
     *   这里是后台线程，但弹窗本身必须在主线程 —— 而宿主 Activity 的查找
     *   也必须在主线程做（ActivityThread.mActivities 不是线程安全的）。
     *   所以整段通过 Handler 投到主线程执行，且用 try/catch 完全包住：
     *   更新提示失败绝不能影响到上报本身。
     *
     * 关于「没拿到 Activity 怎么办」：
     *   用户可能正好在微信的某个没有 Activity 的时刻（比如刚被杀到后台）。
     *   拿不到就直接跳过 —— 每分钟都会再试一次，下一次通常就有了。
     *   这也是为什么不用 «排队等待» 之类的复杂机制：上报本身就在轮询。
     */
    private fun handleUpdate(body: String) {
        if (body.isBlank()) return
        try {
            val json = JSONObject(body)
            if (!json.optBoolean("ok", false)) return
            val upd = json.optJSONObject("update") ?: return

            val latest = upd.optString("latest", "")

            /* ★ 用户第 1 项：每次心跳都把服务端版本号写进缓存，
               好让悬浮面板能「读缓存」而不是自己联网。
               只要服务端给了非空 latest 就缓存 —— 注意这里是**无条件**缓存，
               不依赖 hasUpdate：即使当前版本一致，缓存里也该是最新版本号，
               因为面板要把「一致（灰）/ 不一致（红）」两种情况都显示出来。 */
            if (latest.isNotBlank()) {
                val prev = latestCache.getAndSet(latest)
                if (prev != latest) {
                    Log.i(TAG, "update: latest version cached -> " + latest +
                        " (current=" + com.luoliaoo.wechat.BuildInfo.VERSION_NAME + ")")
                }
            }

            /* ★ 用户第 7 项：判定标准改为「服务端版本与本机是否一致」，
               不再依赖服务端的 hasUpdate 字段。

               用户原话：「版本更新检测 是检测是不是与服务器同一个版本 而不是现在这样的」。
               也就是说：服务端说 hasUpdate=false 但版本号其实不一致时，
               **照样要弹更新** —— 这正是他的要求，不是 bug。

               保留 hasUpdate 的读取只为兜底：两者取「或」。
               为什么用「或」而不是「只用版本比较」：
                 · 版本不一致 → 一定提示（用户的核心诉求）；
                 · 服务端显式说 hasUpdate=true（配置不完整）→ 也提示，
                   免得服务端的意图被客户端的解析吞掉。 */
            val differs = versionsDiffer(latest)
            val serverSaysUpdate = upd.optBoolean("hasUpdate", false)
            if (!differs && !serverSaysUpdate) return

            val url = upd.optString("url", "")
            val whatsNew = upd.optString("whatsNew", "")
            val force = upd.optBoolean("force", false)
            if (latest.isBlank()) return

            /* ★★ v9.0.1 实测修复（真机日志定位）：
               「服务端说版本不一致，但没给下载地址」时**不再走弹窗路径**。

               问题现场（用户真机 logcat）：
                 update: new version available -> 8.1.0 (current=9.0.1 differs=true hasUpdate=false)
                 update: new version 8.1.0 but no download url configured, skip
               现象是用户每分钟被提示一次「发现新版本 8.1.0，但当前无法弹出更新窗」。

               根因：服务端 version.php 的 LL_LATEST_VERSION 还是旧的 8.1.0，
               而本机已经是 9.0.1 —— 两者「不相等」所以按用户第 7 项规则判定为有更新，
               但服务端**没有配置下载地址**（它认为不需要更新，所以不给 url）。
               于是模块拿不到 url、弹不出窗，只能不停地说「无法弹出」。

               为什么「没有 url 就不提示」是对的（而不是继续提示）：
                 更新提示的唯一目的是**让用户拿到新版本**。没有下载地址时，
                 用户即使看到提示也做不了任何事 —— 那就不是提示，而是骚扰。
                 真正的信息（版本号）已经在悬浮面板那一栏用红色显示出来了，
                 用户随时能看到「与本机不一致」，不需要每分钟弹一次。

               所以这里与 UpdateDialog.maybeShow 的既有判断保持一致：
                 maybeShow 本来就有 `if (url.isBlank()) return`，
                 现在在**调用之前**就先拦掉，不再白白走一遍主线程投递。 */
            if (url.isBlank()) {
                Log.i(TAG, "update: version differs ($latest vs " +
                    com.luoliaoo.wechat.BuildInfo.VERSION_NAME +
                    ") but the server gave no download url, not prompting")
                return
            }

            Log.i(TAG, "update: new version available -> " + latest +
                " (current=" + com.luoliaoo.wechat.BuildInfo.VERSION_NAME +
                " differs=" + differs + " hasUpdate=" + serverSaysUpdate +
                " force=" + force + ")")

            val app = com.luoliaoo.wechat.core.HostContext.application() ?: run {
                /* 连 Application 都拿不到 —— 极少见，退到「下一次再试」。
                   不是错误：宿主刚起来时框架字段可能还没填好。 */
                Log.i(TAG, "update: no context yet, will retry on the next report")
                return
            }
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            handler.post {
                try {
                    val activity = hostActivity()
                    com.luoliaoo.wechat.ui.UpdateDialog.maybeShow(
                        activity, latest, url, whatsNew, force,
                    )
                } catch (t: Throwable) {
                    Log.w(TAG, "update: showing the prompt failed: " + t.message)
                }
            }
        } catch (t: Throwable) {
            /* 响应格式不对（比如被运营商劫持插了广告页）——
               静默忽略，下一个周期继续。这里绝不能抛。 */
            Log.w(TAG, "update: cannot parse the update field: " + t.message)
        }
    }

    /**
     * 找宿主当前**正在显示**的 Activity。
     *
     * 走 ActivityThread.mActivities —— 这是模块里已有的做法（MenuHook 也这么取），
     * 比维护自己的 ActivityLifecycleCallbacks 更可靠：
     * 我们是在宿主进程里被注入的，拿不到宿主的 Application 对象来注册回调。
     *
     * 只取 resumed 的那个：只 created/started 的 Activity 窗口还没 attach，
     * 拿它弹 Dialog 可能根本不显示，白白消耗掉一次提示机会
     * （而"每进程只提示一次"的标记已经被置位，那一轮就彻底错过）。
     */
    private fun hostActivity(): android.app.Activity? = try {
        val at = Class.forName("android.app.ActivityThread")
        val thread = at.getDeclaredMethod("currentActivityThread").invoke(null)
        val f = at.getDeclaredField("mActivities")
        f.isAccessible = true
        /* ★★ v9.9.9 关键修复：必须按 **Map** 接收，不能按 HashMap。
         *
         * 真机实测（Android 17）`ActivityThread.mActivities` 的实际类型是
         * `android.util.ArrayMap`，而**不是** `java.util.HashMap`。
         * 原来写的 `as? java.util.HashMap<*, *>` 会**静默返回 null**
         * （`as?` 不抛异常），于是本方法永远返回 null ——
         * 表现为「拿不到宿主 Activity」，而实际上 Activity 一直都在。
         *
         * 后果（这是用户报障的真正根因）：更新弹窗因为拿不到 Activity，
         * 永远退化到 `OverlayHost` 的系统悬浮窗分支 ——
         * 那条路径会把窗口浮在**整个系统**之上，用户切到任何 App 都还看得见，
         * 正是用户要求消灭的「在其他任何地方都会显示」。
         * 修掉这一处，更新窗才会回到宿主 Activity 里正常显示。
         *
         * 同一个错误在 UpdateDialog / MenuHook / DouyinHooks 里都有，已一并修掉。 */
        val entries = (f.get(thread) as? java.util.Map<*, *>)?.values()
        entries?.firstNotNullOfOrNull { rec ->
            try {
                /* ActivityClientRecord.paused == false 表示它正显示在前台。
                   字段名在不同 Android 版本上都是 "paused"，从 API 1 至今没变过。 */
                val pf = rec!!.javaClass.getDeclaredField("paused")
                pf.isAccessible = true
                val paused = pf.get(rec) as? Boolean ?: true
                if (paused) return@firstNotNullOfOrNull null

                val g = rec.javaClass.getDeclaredField("activity")
                g.isAccessible = true
                (g.get(rec) as? android.app.Activity)
                    ?.takeIf { !it.isFinishing && !it.isDestroyed }
            } catch (_: Throwable) {
                null
            }
        }
    } catch (_: Throwable) {
        null
    }

    /**
     * 判断这次响应是不是「可以跟随的跳转」。
     *
     * 只跟同主机：正常场景是服务端把 http 升级成 https（主机名不变），
     * 这种必须跟，否则上报会像这次一样静默失败。
     * 跨主机一律不跟 —— 那是劫持的特征，宁可失败也不能把请求送到别处。
     * 顺带要求目标仍是 https 或 http，避免 file:// 之类的意外协议。
     *
     * @return 可以跟随的目标地址；不该跟则返回 null。
     */
    private fun followSameHost(resp: Response): String? {
        if (resp.code != 301 && resp.code != 302 && resp.code != 307 && resp.code != 308) return null
        val loc = resp.header("Location") ?: return null
        return try {
            val from = ENDPOINT.toHttpUrlOrNull() ?: return null
            val to = loc.toHttpUrlOrNull()
                ?: from.resolve(loc)          // Location 可能是相对路径
                ?: return null
            if (!to.host.equals(from.host, ignoreCase = true)) {
                Log.w(TAG, "usage: refuse cross-host redirect to " + to.host)
                null
            } else {
                to.toString()
            }
        } catch (_: Throwable) {
            null
        }
    }

    /** 向指定地址发一次同样的请求（只用于跟随同主机跳转）。 */
    private fun sendTo(url: String): Boolean {
        val ver = com.luoliaoo.wechat.BuildInfo.VERSION_NAME
        val payload = JSONObject().put("ts", System.currentTimeMillis()).put("v", ver).toString()
        val req = Request.Builder()
            .url(url)
            .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("User-Agent", "LuoLiao/" + ver)
            .build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (resp.isSuccessful) {
                if (lastOkAt.get() == 0L) {
                    Log.i(TAG, "usage: first report ok -> " + body.take(120))
                }
                return true
            }
            Log.w(TAG, "usage: HTTP " + resp.code + " " + body.take(120))
            return false
        }
    }

    // ============================================================ 手动检查更新

    /**
     * 一次手动检查更新的结果。
     *
     * 为什么用一个数据类而不是几个字符串：调用方要拿它做三件事 ——
     * 标题栏显示最新版本号、需要时弹更新窗（那还要下载地址与更新说明）、
     * 以及区分「已是最新」和「检测失败」。参数一多，位置参数就容易传错，
     * 具名字段读起来也不会歧义。
     */
    data class UpdateCheckResult(
        /** "update" = 有新版本；"latest" = 已是最新；"error" = 检测失败。 */
        val status: String,
        /** 服务端给的最新版本号。status 为 "error" 时为空。 */
        val latest: String = "",
        /** 下载地址，status 为 "update" 时才有意义。 */
        val url: String = "",
        /** 更新说明，可能为空。 */
        val whatsNew: String = "",
        /** 是否强制更新。 */
        val force: Boolean = false,
    )

    /**
     * 用户主动点「点我更新」时调用的**按需**检查。
     *
     * 【为什么需要它】原来自动提示只挂在每分钟的后台上报里（见 handleUpdate）。
     * 也就是说：用户想主动看看有没有新版本时，**没有任何入口** —— 只能干等
     * 下一轮轮询，或者自己去官网看。悬浮窗那排「点我更新」要的正是这个入口。
     *
     * 【与后台上报的关系】复用**同一个** ENDPOINT 与同一套请求构造，不新增网络目标、
     * 不新增权限、不改变隐私边界：依然只发「时间戳 + 版本号 + 宿主名」，
     * 服务端在同一个响应里捎回 update 字段。所以「检查更新」与「人数统计」
     * 仍然是同一个请求，不会让服务端多担一份负载。
     *
     * 【结果一律回调，绝不静默】三种情况都会回调，因为「点了没反应」是最糟的体验：
     *   · status = "update" → 有新版本，附带下载地址/说明/是否强制，调用方去弹更新窗
     *   · status = "latest" → 已是最新版本，调用方提示用户
     *   · status = "error"  → 网络不通/被劫持/服务端没配 update 字段，调用方提示「检测失败」
     *
     * 【线程】内部走单线程池（与上报共用 scheduler，保证同一时刻只有一个请求在飞），
     * 回调统一切回**主线程**，调用方可以直接改界面，不必自己再 post。
     */
    fun checkUpdateNow(onResult: (UpdateCheckResult) -> Unit) {
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        val deliver: (UpdateCheckResult) -> Unit = { r ->
            try {
                main.post {
                    try {
                        onResult(r)
                    } catch (t: Throwable) {
                        Log.w(TAG, "update-check: onResult threw: " + t.message)
                    }
                }
            } catch (_: Throwable) {
                // 连主线程都投不进去（极罕见）—— 直接丢弃，绝不抛。
            }
        }

        val task = Runnable {
            val r = try {
                runUpdateCheck(ENDPOINT)
            } catch (t: Throwable) {
                Log.w(TAG, "update-check failed: " + (t.message ?: t.javaClass.simpleName))
                UpdateCheckResult("error")
            }
            deliver(r)
        }

        try {
            val pool = scheduler
            if (pool == null || pool.isShutdown) {
                /* 上报没启动（例如用户关掉了统计开关），或者已经被 stop()。
                   这**不是错误**：手动检查更新本就不该依赖「统计是否开启」，
                   所以这里自己起一条守护线程，而不是直接回 error。 */
                Thread(task).apply { isDaemon = true }.start()
                return
            }
            pool.execute(task)
        } catch (t: Throwable) {
            Log.w(TAG, "update-check: cannot schedule: " + t.message)
            deliver(UpdateCheckResult("error"))
        }
    }

    /**
     * 同步发一次检查请求。只被 [checkUpdateNow] 调用，内部自己处理同主机跳转，
     * 可能抛异常（由调用方兜住）。
     */
    private fun runUpdateCheck(url: String): UpdateCheckResult {
        val ver = com.luoliaoo.wechat.BuildInfo.VERSION_NAME
        val payload = JSONObject()
            .put("ts", System.currentTimeMillis())
            .put("v", ver)
            .put("host", com.luoliaoo.wechat.core.HostApp.current.name)
            /* 手动检查标记：服务端可用它区分「用户主动问」与「后台心跳」，
               将来想做「主动检查不受 24 小时静默期限制」时就有依据。
               服务端不认识这个字段会忽略，不影响兼容。 */
            .put("manual", true)
            .toString()

        val req = Request.Builder()
            .url(url)
            .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("User-Agent", "LuoLiao/" + ver)
            .build()

        http.newCall(req).execute().use { resp ->
            /* 同主机跳转（http→https 升级）要跟随，否则会把正常的
               301 当成检测失败 —— 这正是上报曾经踩过的坑，见 http 的注释。 */
            val location = followSameHost(resp)
            if (location != null) return runUpdateCheck(location)

            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                Log.w(TAG, "update-check: HTTP " + resp.code + " " + body.take(120))
                return UpdateCheckResult("error")
            }
            return parseUpdateResult(body)
        }
    }

    /**
     * 把服务端响应解析成 [UpdateCheckResult]。
     *
     * 注意这里**只解析、不弹窗**：手动检查要先把结果交给调用方（标题栏要显示
     * 「最新版本:XX」），弹不弹窗由调用方决定。自动路径 handleUpdate 是
     * 「解析完直接弹」—— 两种使用方式，所以共用解析、不共用后续动作。
     *
     * 【判定依据：版本号是否一致（用户第 7 项）】
     * 用户明确：「版本更新检测 是检测是不是与服务器同一个版本」。
     * 所以 status 取 "update" 的条件是**本机版本 ≠ 服务端版本**，而不是服务端的
     * hasUpdate 字段。与 handleUpdate 保持同一套规则，两条路径行为一致。
     *
     * 「最新版本」一栏显示服务端返回的 latest 原文：它是就绪待发布的目标版本，
     * 显示它比显示本机版本号更有意义（本机版本号当前就显示在旁边一栏）。
     * 界面再按「与本机是否一致」上色（一致灰、不一致红）。
     */
    private fun parseUpdateResult(body: String): UpdateCheckResult {
        if (body.isBlank()) return UpdateCheckResult("error")
        return try {
            val json = JSONObject(body)
            if (!json.optBoolean("ok", false)) return UpdateCheckResult("error")

            val cur = com.luoliaoo.wechat.BuildInfo.VERSION_NAME
            val upd = json.optJSONObject("update")
                ?: return UpdateCheckResult("latest", latest = cur)

            val latest = upd.optString("latest", "")

            /* 手动检查也顺手刷新缓存 —— 用户点了「点我更新」之后，
               面板上那一栏应当立刻反映刚查到的版本号，而不是等下一轮 60 秒心跳。
               这里是主线程之外的 scheduler 线程，写 AtomicReference 安全。 */
            if (latest.isNotBlank()) latestCache.set(latest)

            /* 两条判定取「或」，与 handleUpdate 完全一致：
               · 版本号不一致 → 有更新（用户的核心规则）
               · 服务端显式 hasUpdate=true 但没给 latest → 也算有更新，不吞掉服务端意图

               ★ v9.0.1: 这里**刻意不**在「没下载地址」时改判为「已是最新」。
               用户明确选择的是「不一致就弹，没地址就提示」——
               手动点击是他主动发起的询问，此时如实告诉他「有差异但没有下载地址」
               比谎称「已是最新」更有用（后者会让他以为检测坏了）。
               
               不打扰的职责放在**自动路径**（见 handleUpdate）：那里没地址就不弹，
               因为它每分钟都会跑，无声地反复提示才是骚扰。两条路径分工不同：
                 · 自动 = 只在能真正交付更新时才出声
                 · 手动 = 用户问了就如实回答 */
            val differs = versionsDiffer(latest)
            val serverSaysUpdate = upd.optBoolean("hasUpdate", false)
            if (differs || serverSaysUpdate) {
                UpdateCheckResult(
                    status = "update",
                    latest = latest.ifBlank { cur },
                    url = upd.optString("url", ""),
                    whatsNew = upd.optString("whatsNew", ""),
                    /* force 如实透传，绝不伪造 —— 它会让更新窗多显示一个「强制更新」警告框，
                       凭空置 true 就是欺骗用户。 */
                    force = upd.optBoolean("force", false),
                )
            } else {
                UpdateCheckResult("latest", latest = latest.ifBlank { cur })
            }
        } catch (t: Throwable) {
            Log.w(TAG, "update-check: cannot parse: " + t.message)
            UpdateCheckResult("error")
        }
    }
}
