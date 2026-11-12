package com.luoliaoo.wechat.voice

import android.os.Handler
import android.os.Looper
import com.luoliaoo.wechat.core.Log
import com.luoliaoo.wechat.core.ModuleBridge
import com.luoliaoo.wechat.core.SoLoader
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import org.luckypray.dexkit.DexKitBridge
import java.io.File
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * v10.0.0: 用**微信自己的**语音转写引擎，把语音变成文字。
 *
 * ══════════════════════════════════════════════════════════════════════════════════════
 * 【整体流程】每次调用 transcribe() 依次做四件事：
 *
 *   1. 查微信本地库 `VoiceTransText` —— 已经转过的，**直接复用，不触发任何转写**；
 *   2. 没查到 -> 反射调 `TransformComponent.submit(msg, false, -1, 0)` 让微信自己转；
 *   3. 轮询 `state(msgId)`：NoTransform -> PreTransform/Transforming -> Transformed；
 *      期间每轮再查一次 `VoiceTransText`（微信转完往往先落库）；
 *   4. `Transformed` 后调 `text(msgId, fileToken)` 取结果；取不到就回落到库里的值。
 *
 * 【为什么用状态机轮询，而不是 hook 回调】
 * 参考实现（yanwai-main）就是轮询，而且轮询在这里是**更稳**的选择：
 * hook 微信内部的完成回调需要再定位一个方法、再赌一次签名；而转写本身只有一两秒，
 * 300ms 一次的轮询既简单又不会漏。轮询期间全程做「聊天未变 / 语音还在」的复核。
 *
 * 【线程模型 —— 本模块没有协程依赖】
 * 参考实现用 kotlinx.coroutines；本项目 **build.gradle.kts 里没有协程依赖**，
 * 全项目也搜不到一处 `kotlinx.coroutines`。所以这里用与项目其余部分一致的做法：
 * 后台线程跑流程 + `Handler(mainLooper)` 把**必须回主线程**的反射调用投递回去并等结果。
 * 宿主组件（TransformComponent）只在主线程碰，这一点与参考实现的约束相同。
 * ══════════════════════════════════════════════════════════════════════════════════════
 */
object WeChatVoice {

    private const val TAG = "LuoLiao"

    // ------------------------------------------------------------------ 常量

    /**
     * DexKit 定位用的**字符串锚点**。
     *
     * 用日志字符串而不是类名：类名每个版本都可能被混淆掉，而日志字符串是开发者写死在代码里的，
     * 跨版本稳定得多 —— 这与 Resolver 用 "OnCreateContextMMMenux..." 定位菜单是同一套思路。
     *
     * 【每个锚点都是真机实测过的，不是照抄来的】
     * 实测环境：微信 8.0.77 (versionCode 3160)，APK 278MB，17 个 dex。
     *
     *   ANCHOR_TALKER    "getTalker returns null."  -> 只在 classes10.dex，1 次
     *   ANCHOR_COMPONENT "MicroMsg.TransformComponent" -> 只在 classes10.dex，1 次
     *
     * 两者同 dex、都是唯一 1 次，所以「声明 getTalker 的类」几乎必然是组件所在的上下文类。
     *
     * 【为什么保留了但不再使用 ANCHOR_COMPONENT_2 / ANCHOR_CHAT_*】
     * 第一版照抄参考实现，用 `usingStrings(A, B)` 双字符串去搜，真机返回 0 个候选
     * （日志：`voice: chatting context not uniquely found (0)`）。
     * 双字符串的匹配语义不如单字符串直观，且 `[notifyDataSetChange]` 在 17 个 dex 里都有，
     * 掺进来只会让条件又窄又不确定。它们留着，是为了记录"这两个字符串确实存在于 dex 中、
     * 只是不适合当锚点"，避免后人再试一遍。
     */
    private const val ANCHOR_TALKER = "getTalker returns null."
    private const val ANCHOR_COMPONENT_1 = "MicroMsg.TransformComponent"

    @Suppress("unused")
    private const val ANCHOR_COMPONENT_2 = "[onChattingPause]"

    @Suppress("unused")
    private const val ANCHOR_CHAT_1 = "MicroMsg.ChattingContext"

    @Suppress("unused")
    private const val ANCHOR_CHAT_2 = "[notifyDataSetChange]"

    /** 轮询间隔。转写通常 1-2 秒完成，300ms 足够细。 */
    private const val POLL_MS = 300L

    /** 总超时。参考实现是 30s；这里沿用 —— 网络差时微信转写确实可能慢。 */
    private const val TIMEOUT_MS = 30_000L

    /**
     * 连续多少轮仍是 NoTransform 就判定失败（**已上传的**语音用这个）。
     *
     * 提交之后微信要先做本地检查（文件在不在、能否解码）才会把状态从 NoTransform 挪走。
     * 5 轮 = 1.5 秒，足够微信启动转写；一直不动说明这条语音转不了（文件缺失等）。
     */
    private const val NO_TRANSFORM_LIMIT = 5

    /**
     * **尚未上传完成**（serverId == 0）的语音，允许它在 NoTransform 上停留的轮数。
     *
     * v10.0.0: 这个常量是「发了语音却转失败」的直接修复。
     *
     * 【为什么需要更长的容忍】刚发出的语音必须先上传，微信才可能转写它。
     * 上传要多久取决于网络，几秒到十几秒都正常。而原来的统一阈值只有 1.5 秒 ——
     * 对"调一下微信自己转过的老语音"够用（库里已有结果，第一轮就命中），
     * 对**刚发出的新语音**则必然失败。实测日志正是这个形态：
     *     voice: id=16079311 initial state=NoTransform   -> 600ms 后就被判死。
     *
     * 30 轮 × 300ms = 9 秒，覆盖正常的上传+启动时间；
     * 再慢的也还有 TIMEOUT_MS（30 秒）兜底，不会无限等下去。
     */
    private const val NEW_VOICE_NO_TRANSFORM_LIMIT = 30

    /** 失败冷却时长：同一个 source 在这段时间内不再重试，避免反复失败刷屏。 */
    private const val FAIL_COOLDOWN_NS = 30_000_000_000L

    // ------------------------------------------------------------------ 状态

    /** 收集到的组件实例（弱引用），最多 [MAX_COMPONENTS] 个。 */
    private class Setup(
        val contract: VoiceHostContract,
        val chatClass: Class<*>,
        val talkerMethod: Method,
    )

    @Volatile
    private var setup: Setup? = null

    @Volatile
    private var installTried = false

    private val components = ArrayList<WeakReference<Any>>()
    private const val MAX_COMPONENTS = 8

    /** 「当前正在看的聊天」。由 observeBinding 更新，离开聊天时清空。 */
    private var activeChat = WeakReference<Any>(null)

    /** 失败冷却表（按 source.key）。 */
    private val failures = object : LinkedHashMap<String, Long>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?) = size > 256
    }

    /**
     * 串行闸门。
     *
     * 转写是"提交 -> 轮询 -> 取结果"的有状态流程，而微信那个组件是**单实例、单任务**的：
     * 同时提交两条会互相踩状态。参考实现用一个 Mutex 把整条流程串起来，这里用一把锁做同一件事。
     */
    private val lane = Object()

    /** 流程执行线程。单线程即可 —— lane 已经保证了串行。 */
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "LuoLiao-voice").apply { isDaemon = true }
    }

    private val main = Handler(Looper.getMainLooper())

    /** 诊断计数。 */
    private val reused = AtomicLong()
    private val transcribed = AtomicLong()
    private val failed = AtomicLong()

    // ------------------------------------------------------------------ 安装

    /**
     * 定位并连接微信的语音转写入口。**只在微信进程内调用一次**。
     *
     * 允许重复调用：DexKit 扫描一次 APK 不便宜，用 [installTried] 挡住第二次。
     */
    fun install(apkPath: String, loader: ClassLoader) {
        if (installTried) return
        installTried = true
        try {
            val wd = ModuleBridge.workDir
            if (!SoLoader.ensureLoaded(ModuleBridge.moduleAppInfo, wd)) {
                Log.w(TAG, "voice: dexkit unavailable; voice-to-text disabled")
                return
            }
            val apk = File(apkPath)
            if (!apk.isFile) {
                Log.w(TAG, "voice: apk missing at " + apkPath)
                return
            }

            DexKitBridge.create(apkPath).use { bridge ->
                // ============================================================ 定位聊天上下文类
                //
                // 【为什么用 getTalker 反推，而不是直接搜 "MicroMsg.ChattingContext"】
                //
                // 第一版照抄参考实现，用
                //     findClass { searchPackages("com.tencent.mm")
                //                 matcher { usingStrings("MicroMsg.ChattingContext", "[notifyDataSetChange]") } }
                // 真机日志给出的是：
                //     voice: chatting context not uniquely found (0)
                // —— 候选数是 **0**，一个都没找到，于是整个功能静默失效。
                //
                // 而 `getTalker returns null.` 这个字符串在真机 dex 里的分布是**唯一且集中**的：
                //     只在 classes10.dex 出现 1 次（其余 16 个 dex 全部为 0）
                // 同一个 classes10.dex 也是 `MicroMsg.TransformComponent`（1 次）所在的 dex。
                // 所以「声明 getTalker 的那个类」就是聊天上下文类，**比猜字符串组合可靠得多**。
                //
                // 这就是"用弱锚点不如用强锚点"：`[notifyDataSetChange]` 那种字符串在 17 个 dex 里
                // 到处都有，拼在一起反而让 DexKit 的匹配条件变得又窄又不确定。
                val talkerFound = bridge.findMethod {
                    matcher { usingStrings(ANCHOR_TALKER) }
                }
                Log.i(TAG, "voice: getTalker candidates=" + talkerFound.size)

                val talkerMethod = talkerFound
                    .mapNotNull { f -> runCatching { f.getMethodInstance(loader) }.getOrNull() }
                    // 二次签名过滤：0 参数、返回 String。
                    // 只用字符串不够 —— 那句日志可能在多个类里出现，必须再钉一次签名形状。
                    .firstOrNull { it.parameterCount == 0 && it.returnType == String::class.java }
                if (talkerMethod == null) {
                    Log.w(TAG, "voice: getTalker() not found; cannot identify the chatting context")
                    return
                }
                val chat = talkerMethod.declaringClass
                Log.i(TAG, "voice: chatting context = " + chat.name + " (via getTalker)")

                // ============================================================ 定位转写组件类
                //
                // 用**单个**字符串搜，再用「能否解析出契约」做二次筛选。
                //
                // 【为什么不直接 singleOrNull】DexKit 的 `usingStrings` 传多个字符串时，
                // 匹配语义是"引用了其中任意一个"还是"全部"在版本之间并不直观；一旦语义偏宽，
                // `[onChattingPause]` 就会把 6 个 dex 里的无关类全带进来，候选数暴涨、
                // 唯一性检查必然失败，而失败信息只会说"没找到唯一候选"，看不出是被噪声淹了。
                //
                // 真正可靠的特征不是字符串，而是**这个类能不能解析出我们要的三个方法形状**：
                // VoiceHostContract.resolve 会依次要求 submit / state / text 三个签名各自**唯一**。
                // 拿它当筛子，就不必赌 DexKit 的匹配语义了。
                val componentCandidates = bridge.findClass {
                    matcher { usingStrings(ANCHOR_COMPONENT_1) }
                }
                Log.i(TAG, "voice: transform component candidates=" + componentCandidates.size)

                var contract: VoiceHostContract? = null
                var component: Class<*>? = null
                for (candidate in componentCandidates) {
                    val cls = loadClass(candidate.name, loader) ?: continue
                    val c = VoiceHostContract.resolve(cls, chat)
                    if (c != null) {
                        component = cls
                        contract = c
                        break
                    }
                }
                if (component == null || contract == null) {
                    Log.w(
                        TAG,
                        "voice: no candidate matched the transform contract (" +
                            componentCandidates.size + " candidate(s) tried)",
                    )
                    return
                }

                Log.i(
                    TAG,
                    "voice: contract resolved -- component=" + component.name +
                        " chat=" + chat.name +
                        " submit=" + contract.submit.name +
                        " state=" + contract.state.name +
                        " text=" + contract.text.name +
                        " msgClass=" + contract.messageClass.name,
                )

                // ---- 收集组件实例：hook 所有构造 + 所有非静态非抽象方法 ----
                //
                // 为什么要这么多 hook：组件实例是在聊天界面构建时被 new 出来的，
                // 之后它的方法会被反复调用。任何一个时机拿到 thisObject 都行，
                // 多铺几个入口才能保证"用户打开聊天后我们一定有它的实例"。
                //
                // hookAllConstructors 必须整批成功或整批回滚：半套 hook 会留下
                // 能收到实例但收不全的状态，静默地让功能时好时坏。
                val hooks = ArrayList<XC_MethodHook.Unhook>()
                try {
                    val observer = object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            param.thisObject?.let { observe(it) }
                        }
                    }
                    hooks.addAll(XposedBridge.hookAllConstructors(component, observer))
                    for (m in component.declaredMethods) {
                        if (Modifier.isStatic(m.modifiers) || Modifier.isAbstract(m.modifiers)) continue
                        hooks.add(XposedBridge.hookMethod(m, observer))
                    }
                } catch (t: Throwable) {
                    hooks.forEach { runCatching { it.unhook() } }
                    Log.w(TAG, "voice: instance collection failed: " + t.javaClass.simpleName + ": " + t.message)
                    return
                }

                setup = Setup(contract, chat, talkerMethod)
                Log.i(
                    TAG,
                    "voice: READY -- 微信语音转文字已连接 (hooks=" + hooks.size +
                        " msg=" + contract.messageClass.name + ")",
                )
            }
        } catch (t: Throwable) {
            Log.w(TAG, "voice: install failed: " + t.javaClass.simpleName + ": " + t.message)
        }
    }

    /** 用宿主 loader 载入类。**绝不能用模块自己的 loader** —— 那会拿到另一个同名 Class。 */
    private fun loadClass(name: String, loader: ClassLoader): Class<*>? = try {
        Class.forName(name, false, loader)
    } catch (_: Throwable) {
        null
    }

    /** 记下一个组件实例（弱引用，头插，去重，上限 8）。 */
    @Synchronized
    private fun observe(component: Any) {
        components.removeAll { it.get() == null || it.get() === component }
        components.add(0, WeakReference(component))
        while (components.size > MAX_COMPONENTS) components.removeAt(components.lastIndex)
    }

    /** 是否已经连上。UI / 日志用它如实说明功能是否可用。 */
    val isReady: Boolean get() = setup != null

    // ------------------------------------------------------------------ 绑定的聊天

    /**
     * 记录「当前正在看哪个聊天」。
     *
     * 【为什么必须记】转写要好几秒。这几秒里用户可能切走，如果只看"组件实例里的 talker"，
     * 就可能把 A 聊天的转写结果写进 B 聊天。这里从绑定对象里取它持有的 ChattingContext，
     * 作为"此刻屏幕上是哪个聊天"的权威答案，选组件实例时优先与它同一引用（===）的那个。
     *
     * @param owner 正在绑定消息行的对象（Adapter / ViewHolder 皆可）
     */
    fun observeBinding(owner: Any?) {
        val config = setup ?: return
        if (owner == null) return
        try {
            val field: Field = generateSequence(owner.javaClass) { it.superclass }
                .flatMap { it.declaredFields.asSequence() }
                .firstOrNull { it.type == config.chatClass && !Modifier.isStatic(it.modifiers) }
                ?: return
            field.isAccessible = true
            synchronized(this) { activeChat = WeakReference(field.get(owner)) }
        } catch (_: Throwable) {
            // 找不到绑定字段是正常的（不是每个绑定对象都持有 context），静默即可
        }
    }

    /** 离开聊天时调用，立刻作废 activeChat，防止结果落到别的聊天。 */
    fun clearActiveChat() {
        synchronized(this) { activeChat.clear() }
    }

    /** 清空失败冷却（用户主动重试时用）。 */
    fun retryFailures() {
        synchronized(failures) { failures.clear() }
    }

    /**
     * 挑出属于 [source] 那个聊天的组件实例。**只在主线程调用。**
     *
     * 规则（与参考实现一致）：
     *   1. 先按 `getTalker() == source.talker` 过滤；
     *   2. 优先返回其 chatField 与 activeChat **同一引用**的那个（最准）；
     *   3. 否则只有唯一候选才接受；
     *   4. 多于一个且认不出当前聊天 -> 报错，**绝不赌**。
     */
    private fun componentFor(config: Setup, source: VoiceSource): Any {
        val candidates = synchronized(this) { components.mapNotNull { it.get() } }.filter { c ->
            try {
                val chat = config.contract.chatField.get(c)
                config.talkerMethod.invoke(chat) == source.talker
            } catch (_: Throwable) {
                false
            }
        }
        val current = synchronized(this) { activeChat.get() }
        candidates.firstOrNull { c ->
            current != null && config.contract.chatField.get(c) === current
        }?.let { return it }
        return candidates.singleOrNull()
            ?: error("\u8bed\u97f3\u8f6c\u5199\u5c1a\u672a\u8fde\u63a5\u5f53\u524d\u804a\u5929\uff0c\u8bf7\u9000\u51fa\u540e\u91cd\u65b0\u8fdb\u5165")
    }

    // ------------------------------------------------------------------ 主流程

    /**
     * 把一条语音转成文字。**阻塞**，调用方请在后台线程使用。
     *
     * @param source  语音身份
     * @param allowed 每轮轮询都会调用一次的复核：返回 false 表示"该放弃了"
     *                （用户切了聊天 / 关了开关 / 页面已销毁）。返回 false 时返回 null，
     *                且**不记入失败冷却** —— 那不是失败，是主动放弃。
     * @return 转写文本；未能转写返回 null（原因已写入日志）
     */
    fun transcribe(source: VoiceSource, allowed: () -> Boolean): String? {
        // 串行：转写组件是单任务的有状态对象，两条并行会互相踩状态。
        synchronized(lane) {
            try {
                // 1) 先复用微信已经转好的结果。这一步不发网络请求、不触发引擎。
                val existing = WeChatVoiceDb.existingText(source)
                if (!existing.isNullOrBlank()) {
                    reused.incrementAndGet()
                    Log.i(TAG, "voice: reused existing transcript for id=" + source.id)
                    return existing
                }

                val config = setup
                if (config == null) {
                    Log.w(TAG, "voice: not ready; cannot transcribe id=" + source.id)
                    return null
                }

                // 2) 失败冷却：刚失败过的直接跳过，避免每个绑定周期都重试一遍。
                val failedAt = synchronized(failures) { failures[source.key] }
                if (failedAt != null && System.nanoTime() - failedAt < FAIL_COOLDOWN_NS) {
                    Log.i(TAG, "voice: in failure cooldown, skipping id=" + source.id)
                    return null
                }

                // 3) 从库里把宿主自己的消息对象还原出来（引擎要的是它，不是我们拼的）。
                val msg = WeChatVoiceDb.messageFromDb(source, config.contract.messageClass)
                if (msg == null) {
                    Log.w(TAG, "voice: cannot restore the message row for id=" + source.id)
                    return null
                }

                // 4) 在主线程读状态、必要时提交。
                val state = onMain {
                    val host = componentFor(config, source)
                    val st = (config.contract.state.invoke(host, source.id) as Enum<*>).name
                    // 只在自己是 NoTransform 时才提交：已经有任务在跑就等它，
                    // **绝不去打断**用户手动触发的转写（参考实现的同款约束）。
                    if (st == "NoTransform") {
                        config.contract.submit.invoke(host, msg, false, -1, 0)
                    }
                    st
                }
                if (state == null) {
                    Log.w(TAG, "voice: could not reach the main thread")
                    return null
                }
                Log.i(TAG, "voice: id=" + source.id + " initial state=" + state)

                // 5) 轮询。
                val deadline = System.currentTimeMillis() + TIMEOUT_MS
                var noTransform = 0
                while (System.currentTimeMillis() < deadline) {
                    if (!allowed()) {
                        Log.i(TAG, "voice: abandoned (chat changed) id=" + source.id)
                        return null
                    }
                    Thread.sleep(POLL_MS)

                    // 语音被删/撤回/换账号 -> 放弃，否则会给一条不存在的语音留记录。
                    if (!WeChatVoiceDb.stillMatches(source)) {
                        Log.w(TAG, "voice: the message vanished or the account changed, id=" + source.id)
                        return null
                    }

                    val cur = onMain {
                        val host = componentFor(config, source)
                        (config.contract.state.invoke(host, source.id) as Enum<*>).name
                    } ?: return null

                    if (cur == "Transformed") {
                        val text = onMain {
                            val host = componentFor(config, source)
                            (config.contract.text.invoke(host, source.id, source.fileToken) as? String)?.trim()
                        }
                        if (!text.isNullOrBlank()) {
                            transcribed.incrementAndGet()
                            Log.i(TAG, "voice: transcribe OK id=" + source.id + " len=" + text.length)
                            return text
                        }
                    }

                    // 每轮都再查一次库：微信经常先把结果落库、状态稍后才变。
                    // 但 PreTransform/Transforming 期间不认 —— 那可能是上一轮的旧值。
                    val stored = WeChatVoiceDb.existingText(source)
                    if (!stored.isNullOrBlank() && cur !in setOf("PreTransform", "Transforming")) {
                        transcribed.incrementAndGet()
                        Log.i(TAG, "voice: transcribe OK from db id=" + source.id + " len=" + stored.length)
                        return stored
                    }

                    if (cur == "NoTransform") {
                        noTransform++
                        /* ★ v10.0.0：NoTransform 的容忍时间必须分两种情况，否则新语音必失败。
                         *
                         * 【原来的问题】NO_TRANSFORM_LIMIT = 5、POLL_MS = 300ms，
                         * 也就是**只等 1.5 秒**就放弃。对"已经在微信里转过的语音"够用（库里有结果，
                         * 第一轮就命中），但对**刚发出的语音**远远不够 —— 它要先上传，
                         * 微信才开始转写。实测日志里新语音就是这样被判死的。
                         *
                         * 【判据】`serverId == 0` 说明这条消息**还没拿到服务器 id**，即尚未上传完成。
                         * 此时 NoTransform 是**正常的、临时的**，必须继续等；
                         * 反之 serverId 已有值却仍停在 NoTransform，多半是微信真不打算转
                         * （例如语音文件已不在本机），这时按原来的短阈值退出即可，不必白等。
                         *
                         * 上限仍然受 TIMEOUT_MS（30 秒）约束，所以最坏情况不会失控。 */
                        val limit = if (source.serverId == 0L) {
                            NEW_VOICE_NO_TRANSFORM_LIMIT
                        } else {
                            NO_TRANSFORM_LIMIT
                        }
                        if (noTransform >= limit) {
                            Log.w(TAG, "voice: WeChat did not start the transform id=" + source.id)
                            break
                        }
                    }
                }

                // 走到这里 = 超时 / 微信没动。记冷却，让调用方在 30 秒内不必再试。
                failed.incrementAndGet()
                synchronized(failures) { failures[source.key] = System.nanoTime() }
                Log.w(TAG, "voice: transcribe FAILED id=" + source.id)
                return null
            } catch (t: Throwable) {
                failed.incrementAndGet()
                try {
                    synchronized(failures) { failures[source.key] = System.nanoTime() }
                } catch (_: Throwable) {
                    // 冷却表的问题不该盖住真实异常
                }
                Log.w(TAG, "voice: transcribe threw: " + t.javaClass.simpleName + ": " + t.message)
                return null
            }
        }
    }

    /**
     * 把一段代码投递到主线程执行并等它返回。
     *
     * 【为什么必须回主线程】宿主组件是按主线程模型写的，从别的线程调它的方法属于未定义行为。
     * 【为什么要"等"】读状态与取结果是流程的同步步骤，值要立刻用于下一步判断，不能 fire-and-forget。
     * 【为什么有超时】主线程可能正被一件长事占住；无限等会让这条转写线程永远挂着。
     * 1 秒没排上就放弃这一轮 —— 反正外面在轮询，下一轮还会再试。
     */
    private fun <T> onMain(body: () -> T): T? {
        if (Looper.myLooper() === Looper.getMainLooper()) {
            return try {
                body()
            } catch (t: Throwable) {
                Log.w(TAG, "voice: main-thread step failed: " + t.message)
                null
            }
        }
        var out: T? = null
        var err: Throwable? = null
        val latch = CountDownLatch(1)
        val posted = main.post {
            try {
                out = body()
            } catch (t: Throwable) {
                err = t
            } finally {
                latch.countDown()
            }
        }
        if (!posted) return null
        val done = try {
            latch.await(1000L, TimeUnit.MILLISECONDS)
        } catch (t: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!done) {
            Log.w(TAG, "voice: main thread did not answer within 1s")
            return null
        }
        if (err != null) {
            Log.w(TAG, "voice: main-thread step failed: " + err?.message)
            return null
        }
        return out
    }

    /** 诊断串，给设置界面的「运行状态」用。 */
    fun diag(): String = "voiceReady=" + isReady +
        " components=" + synchronized(this) { components.count { it.get() != null } } +
        " dbHandles=" + WeChatVoiceDb.handleCount() +
        " reused=" + reused.get() + " transcribed=" + transcribed.get() + " failed=" + failed.get()

    /** 让调用方在后台线程池上跑转写，不阻塞绑定流程。 */
    fun submitAsync(source: VoiceSource, allowed: () -> Boolean, onDone: (String?) -> Unit) {
        try {
            worker.execute {
                val text = transcribe(source, allowed)
                if (text != null) {
                    try {
                        onDone(text)
                    } catch (t: Throwable) {
                        Log.w(TAG, "voice: onDone failed: " + t.message)
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "voice: could not queue the task: " + t.message)
        }
    }
}
