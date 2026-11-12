import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.luoliaoo.wechat"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.luoliaoo.wechat"
        minSdk = 29
        targetSdk = 36
        // Bumped on every build: the previous three builds all shipped
        // versionCode=1, which made "which build is installed?" unanswerable.
        // v4.1.0: 编辑模板 gains the per-template Skill manager (feature bump, so the second digit moves).
        // v5.0.0: Douyin becomes a second supported host -- a major feature, so the leading digit moves.
        // v6.0.0: Soul becomes a third supported host -- same reasoning as v5.0.0, leading digit moves again.
        // v7.0.0: Kuaishou becomes a fourth supported host -- same reasoning again, leading digit moves.
        // v7.0.1: Kuaishou multi-select 分析 button fixed (it was injected into the wrong surface, then
        //         cloned a 1px divider's params), and the build now has TWO DISTINCT flavours:
        //         release = NO logging at all, debug = full verbose logging. The third digit moves
        //         because no new host was added.
        // v7.0.2: a COPY button to the right of the text-polish PREVIEW header (opened by long-pressing
        //         the balloon); it copies the previewed sentence to the system clipboard. Third digit
        //         again -- a feature added to an existing surface, not a new host.
        // v8.0.0: QQ (com.tencent.mobileqq 9.3.65) becomes a FIFTH supported host -- same reasoning as
        //         v5.0.0/v6.0.0/v7.0.0, so the leading digit moves again. NOTE this bump is for the
        //         WIRING only: the host resolves, appears in the scope list and version badges, and
        //         refuses to install anything it cannot serve. The QQ hook implementations land in
        //         follow-up builds, which move the third digit. Do not describe this build to the user
        //         as "QQ is supported" -- it is "QQ is recognised".
        // v8.1.0: the QQ host is now actually FEATURED, and this is the build that completes it:
        //         long-press menu injection (分析 + 浮窗), multi-select 分析 in the bottom toolbar,
        //         local history capture, and compatibility with QQ 9.1.97 as well as 9.3.65.
        //         The MIDDLE digit moves: v8.0.0 wired a new host, this adds the features to it --
        //         the same distinction v7.0.x drew between "host added" and "features added".
        //         Also in this build: the balloon SIZE control beside the transparency one, both with
        //         the module's own dialog UI, and five non-text placeholders added to the default
        //         block words.
        // v9.0.0: LIQUID GLASS. The whole module UI is rebuilt on a liquid-glass design language
        //         and -- for the first time -- supports BOTH a light and a dark theme, following the
        //         system setting. The leading digit moves because this is a change of design
        //         LANGUAGE rather than a feature bolted onto the old one: 14 files and 700+ call
        //         sites, unified behind one theme hub (ui/NukeTheme.kt) that now resolves every
        //         colour per theme.
        //
        //         NO new host is added, so NO scope change is needed: every host the module already
        //         served gets the new look for free, because this UI is the module's own (drawn in
        //         code) rather than the host's.
        //
        //         Also in this build, all from an independent accessibility/contrast audit:
        //           * placeholder/hint text raised from 3.31:1 and 4.12:1 to >=5.2:1 (WCAG AA);
        //           * the danger border made visible against its own fill (was 1.99:1);
        //           * the light-theme switch knob separated from its track (was 1.63:1);
        //           * touch targets raised toward the 48dp guidance (the module's own link control
        //             measured ~26dp and is used in 8 places).
        // v9.0.1: LAYOUT POLISH, from the user's own list of what looked wrong on screen.
        //         The middle digit moves -- same design language, tightened layout:
        //           * the floating panel's header now holds its title plus FOUR buttons on ONE row
        //             (官网 / 关闭 / 透明 / 大小), all the same width and height. Before, 透明 and 大小
        //             sat on a second row of their own, so the panel wasted a whole row and the
        //             buttons were visibly different sizes because each sized to its own label.
        //           * the three contact lines (TG / QQ / 酷安) are now ONE row instead of three --
        //             they are a footer, not content, and three full rows pushed everything below
        //             them further down the panel.
        //           * a version row: 当前版本 / 最新版本 / 点我更新. The last one is a TEXT LINK, not
        //             a button, and tapping it runs a REAL update check. This is a new capability:
        //             until now there was NO way for a user to ask "is there a new version?" --
        //             the check only happened incidentally inside the 60-second usage heartbeat.
        //           * the 分析范围 / 多选 dialogs are made compact: the 选择范围 heading matches
        //             选择模板's type size (it was 22f against 15f, which read as an accidental
        //             second-level title), the template list shows at most 4 rows and scrolls, and
        //             the read-back list shows at most 5 messages and scrolls, so neither dialog
        //             grows off the bottom of the screen.
        // v9.2.0: TIM joins the QQ host. TIM (腾讯征程) is a SEPARATE APK with a separate package, but
        //         it runs the SAME NT chat engine, so it is served by the SAME hooks -- measured, not
        //         assumed: the shipped TIM 4.1.0 APK was unpacked and every class this module hooks on
        //         QQ was found under the same names (the long-press menu container and its no-icon twin,
        //         the menu item type, BubblePopupWindow, AIOMsgItem, MsgRecord, AbsMsgListVB, the input
        //         box, the multi-select event). So this is the MIDDLE digit: features added to an
        //         existing host, not a new host.
        //
        //         TIM and QQ are ONE host because they share the hook set, but TWO products, so the
        //         brand name is resolved PER PACKAGE -- a TIM user reads 「TIM 4.1.0」, never 「QQ 4.1.0」.
        //         The scope array and the manifest <queries> block gained com.tencent.tim, which is also
        //         what makes its version visible to the module on Android 11+.
        //         （`ctx` / `context` / `this@SettingsActivity`），一次全局替换必然编译报错。
        // v10.0.0: 语音转文字。**首位进位**，理由与 v5/v6/v7/v8/v9.5 加宿主时一致 —— 这是
        //         一类新的能力（读宿主自己的媒体消息、驱动宿主自己的转写引擎），不是既有界面上的
        //         一个小改动。
        //
        //         它做了什么：微信里出现一条语音消息时，调用**微信自带的**转写引擎
        //         （`TransformComponent`）把语音转成文字，以 `[语音转文字]xxx` 写入本地聊天记录。
        //         另外还修掉一个既存缺陷：语音的 `field_content` 是整段 `<voicemsg aeskey=... >`
        //         XML，旧代码因为没有 `isText` 门禁，把这段 XML 原样写进了本地记录。
        //
        //         只影响微信：转写引擎是微信独有的类，其它宿主的 type 编号也各不相同，
        //         所以 Code 里对 `HostApp.WECHAT` 做了显式门禁，其它宿主的行为一个字都没变。
        //
        //         新增设置项：`语音转文字` 开关（默认开），在「诊断」卡片里。
        //         关闭后语音既不触发转写、也不写进本地记录。
        // v10.1.0: 界面精简与发布形态调整。**中间位进位** —— 已有功能上的改动，
        //         既没有新增宿主（那会动首位，如 v5/v6/v7/v8/v9.5），
        //         也不是一次设计语言的更换（如 v9.0.0）。三件事：
        //
        //           1. 移除模块 App 首页的「优化模板」卡片（用户要求）。只删**入口卡片** ——
        //              优化模板仍可在微信内「文本优化」对话框里管理，PolishTemplateStore
        //              的挂载也照旧。删入口 ≠ 删功能，代码里对这条边界有显式注释。
        //           2. 安装后**桌面不显示图标**。LauncherActivity 的 intent-filter 由
        //              CATEGORY_LAUNCHER 改为 CATEGORY_INFO：桌面不再建图标，而
        //              getLaunchIntentForPackage() 会优先查 INFO 类别，所以 LSPosed 详情页的
        //              「打开」按钮依然有效 —— 用户不会被锁在配置界面之外。
        //           3. 同版修掉语音转写的三个真机 bug：转写立即落盘（不再等下一条消息）、
        //              stillMatches 只校验身份（不再把字段回填误判为消息被删）、
        //              新语音的 NoTransform 容忍时间由 1.5 秒放宽到 9 秒（它要先上传）。
        // v10.2.0: 三处界面/功能增补。**中间位进位** —— 与前几版同样的判据：
        //          没有新增宿主（那动首位）、不是设计语言更换（那也动首位），
        //          只是在已有界面上加东西 + 修 bug。
        //
        //           1. **手动分析**：分析记录面板的标题栏在「官网」左侧新增「手动」按钮，
        //              打开一个可手输/一键粘贴文本的弹窗（选择模板 → 点我一键粘贴 → 输入框
        //              → 配置/取消/确定），复用「仅选择信息」那条分析通路，不另写 AI 调用。
        //              标题栏原本 4 个按钮已压在上限（标题只剩约 69.7dp，需要 56dp），
        //              所以同版把按钮 56dp→36dp、间距 4dp→2dp，五个按钮 190dp，
        //              标题反而比改动前更宽裕（119.7dp），文案 32dp 文字区放得下两字标签。
        //           2. **模板导入/导出**：模板管理处「添加」下面加导入、「恢复默认」下面加导出。
        //              导出打包为 zip 走系统分享，**默认不含 key**（可勾选带上）；
        //              导入走系统文件选择器（复用既有的 FilePicker，它 hook 宿主 Activity 的
        //              结果回调 —— 因为模块 UI 跑在宿主进程里、自己没有 Activity 可覆写），
        //              支持预览与逐条勾选，重名**跳过不覆盖**（覆盖会不可逆地销毁用户改过的模板）。
        //           3. **模板新建/编辑 UI**：取消/保存由系统按钮位改为模块自己的玻璃按钮
        //              （与模板管理页操作条同款），并在名称为空时给行内红字提示而非静默关窗。
        //           4. 顺带修：**微信「引用」消息**显示为 `[引用]标题"引用：正文"` 而不是原始 XML。
        //              引用是 type=57 的 appmsg，被引用原文在 `<refermsg>` 段内；
        //              `displayText()` 是"要显示/要发给模型"的唯一入口，本次把引用也在那里就地转换
        //              （这是同一根因的第 5 处，前四处是语音/多选/长按预览/送模型）。
        //              另外修正语音转写读取：原来 `WHERE msgId=? AND cmsgId=?` 组合查询是错的，
        //              msgId 是主键、单查即唯一，加 cmsgId 只会造成假阴性；改为与微信一致的分开查。
        // v10.3.0: 追问 AI 改为流式输出（SSE 打字机）+ 设置页新增出字速度滑杆。
        //          中间位进位：在已有界面/功能上的改动，不新增宿主。
        // v10.3.1: ① 分析卡片每条回复的「编辑」旁新增「复制」；
        //          ② 文本优化模板新增 API Base URL / API Key / 模型，可自选模型。
        //          同上，仍是已有界面上的功能新增，不新增宿主 → 只进第三位。
        versionCode = 1031
        versionName = "10.3.1"
    }

    // 签名证书凭据从 local.properties 读取（该文件不进版本库）。
    // 缺少这些键时 release 构建会失败，这是有意的：宁可构建失败，也不让密码进仓库。
    val localProps = Properties().apply {
        val f = rootProject.file("local.properties")
        if (f.exists()) {
            val stream = f.inputStream()
            try { load(stream) } finally { stream.close() }
        }
    }
    fun prop(key: String, fallback: String): String =
        (localProps.getProperty(key) ?: System.getenv(key) ?: fallback)

    signingConfigs {
        create("release") {
            storeFile = rootProject.file(prop("LUOLIAO_STORE_FILE", "luoliao.jks"))
            storePassword = prop("LUOLIAO_STORE_PASSWORD", "")
            keyAlias = prop("LUOLIAO_KEY_ALIAS", "luoliao")
            keyPassword = prop("LUOLIAO_KEY_PASSWORD", "")
        }
    }

    // ============================================================ v3.0.1 -> FIXED MODULE LOAD FAILURE
    //
    // v3.0.0 compiled the local de.robv stubs INTO the APK by adding src/stub/java to the `main`
    // source set:
    //
    //     sourceSets.getByName("main") { java.srcDir("src/stub/java") }
    //
    // LSPosed refuses such a module outright. Captured on device:
    //
    //     E/LSPosedFramework: (com.tencent.mm)[framework,XposedBridge] Cannot load module: com.luoliaoo.wechat
    //     E/LSPosedFramework: The Xposed API classes are compiled into the module's APK.
    //     E/LSPosedFramework: This may cause strange issues and must be fixed by the module developer.
    //
    // Dex parsing proved it: the shipped classes.dex DEFINED 11 de/robv/android/xposed/* classes,
    // while WeKit's APK defines ZERO (it only references them as types).
    //
    // WeKit avoids this with `compileOnly(libs.legacyxposed.api)` (de.robv.android.xposed:api:82) plus
    // `compileOnly(project(":libs:common:stubs"))`. That artifact is not resolvable from this project's
    // repositories, so the API surface stays in app/src/stub/java -- but it is now wired in as a
    // COMPILE-ONLY dependency, so javac/kotlinc see it while the dex never contains it.
    //
    // Only `main` participates in the APK variant, so nothing here can leak the stubs back in.

    // ============================================================ v7.0.1: TWO LOG FLAVOURS
    //
    // The user asked for two builds: **release with NO logs at all**, and **debug with very detailed
    // logs**. A runtime switch was NOT enough here, for three reasons:
    //
    //   1. "No logs" has to mean the strings are not in the binary. A switch that returns early still
    //      ships every message as a dex string, and a curious user (or a moderator) can read them all.
    //      `LOG_ENABLED = false` lets R8's constant folding delete the call sites entirely.
    //   2. Logging inside a hook runs on the HOST's main thread; the cheapest logging is the call that
    //      was never emitted.
    //   3. A module that logs on every message the user reads has a real privacy cost. The release build
    //      is the one people install to just use it, so it should be silent by construction.
    //
    // WHY `isMinifyEnabled = true` MATTERS FOR CORRECTNESS, NOT SIZE: `Log` now guards each method with
    // `if (!LOG_ENABLED) return 0`. Kotlin compiles `const val` to an inlined literal, so the guard is a
    // constant-false branch; R8 then removes the branch AND the now-unreachable message strings. Without
    // R8 the strings would survive. This is the only reason minification is switched on, and it is the
    // reason `release` must keep `isShrinkResources = false` + the keep rules below: shrinking resources
    // would be fine, but the module is tiny and a resource mistake here is expensive.
    //
    // `LOG_ENABLED` is a BuildConfig FIELD rather than `BuildConfig.DEBUG` so the intent is explicit and
    // readable in the generated source, and so a future "release-with-logs" flavour is a one-line change
    // instead of a hunt through `if (BuildConfig.DEBUG)` sites.
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("release")
            buildConfigField("boolean", "LOG_ENABLED", "false")
            // Every hook entry point is reached reflectively by the framework (through
            // assets/xposed_init), so R8 cannot see the edge and would otherwise strip or rename them.
            // The rules file is the single place that keeps the module loadable.
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            isMinifyEnabled = false
            // Full logging: every message the module writes reaches both logcat and its own log file.
            buildConfigField("boolean", "LOG_ENABLED", "true")
            // Distinguishes the two installed builds in the launcher, so "which one is on the phone?"
            // is answerable without opening anything.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // libxposed 妯″潡璧勬簮蹇呴』鍚堝苟锛屽惁鍒欐ā鍧楁棤娉曡璇嗗埆
    packaging {
        // v3.0.0: no META-INF/xposed. The module registers ONLY through assets/xposed_init,
        // which is what WeKit's legacy build ships -- so a framework can never take the
        // libxposed entry point this module no longer implements.
    }

    // 妯″潡鍙緷璧栫紪璇戞湡 API锛屼笉闇€瑕佹墦杩?APK
    buildFeatures {
        buildConfig = true
    }

    testOptions {
        unitTests {
            // android.util.Log 绛夊湪 JVM 鍗曟祴涓棤瀹炵幇锛岃繑鍥為粯璁ゅ€艰€屼笉鏄姏寮傚父
            isReturnDefaultValues = true
        }
    }

    // ============================================================ v7.0.1: TWO APKs, DISTINCT NAMES
    //
    // Both flavours used to write `app-release.apk` / `app-debug.apk` with no version in the name, so
    // copying "the APK" to the desktop was ambiguous the moment two builds existed. A stale copy that
    // LOOKED current is exactly what made an earlier fix appear not to work ("the fix was never in the
    // build"). The name now carries the version and the flavour, so what is on disk says what it is.
    applicationVariants.all {
        val variant = this
        outputs.all {
            val out = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            // `variant.versionName` already carries the "-debug" suffix (see versionNameSuffix), so
            // the flavour is NOT appended again here -- that produced "luoliao-7.0.1-debug-debug.apk".
            out.outputFileName = "luoliao-" + variant.versionName + ".apk"
        }
    }
}

// ============================================================ v3.0.1: compile-only de.robv API
//
// Compiles app/src/stub/java into a standalone jar placed on the COMPILE classpath only.
// It is deliberately NOT part of any Android source set, so the dex can never contain
// de/robv/android/xposed/* -- the condition LSPosed rejects.
val stubApiClasses = layout.buildDirectory.dir("stubApi/classes")
val stubApiJar = tasks.register<Jar>("stubApiJar") {
    archiveFileName.set("de-robv-xposed-api-stub.jar")
    destinationDirectory.set(layout.buildDirectory.dir("stubApi"))
    from(stubApiClasses)
}

val compileStubApi = tasks.register<JavaCompile>("compileStubApi") {
    source = fileTree("src/stub/java") { include("**/*.java") }
    // The de.robv API surface references android.content.pm.ApplicationInfo, so android.jar must be
    // on this task's classpath. It is ONLY used to compile the stubs; it is not packaged anywhere.
    classpath = files("${android.sdkDirectory}/platforms/android-${android.compileSdkVersion!!.removePrefix("android-")}/android.jar")
    destinationDirectory.set(stubApiClasses)
    sourceCompatibility = JavaVersion.VERSION_17.toString()
    targetCompatibility = JavaVersion.VERSION_17.toString()
}

stubApiJar.configure { dependsOn(compileStubApi) }

dependencies {
    // WeKit: compileOnly(libs.legacyxposed.api) == de.robv.android.xposed:api:82
    // Kept local because that artifact is unreachable here; the compileOnly configuration is what
    // matters -- it is never packaged, so the framework's own de.robv implementation wins at runtime.
    compileOnly(files(stubApiJar))

    // libxposed API 102 鈥斺€?蹇呴』 compileOnly锛屾墦杩?APK 浼氳 LSPosed 鎷掔粷鍔犺浇
    // v3.0.0: WEKIT'S LEGACY PATH -- the traditional de.robv API, registered through
    // assets/xposed_init only. This is what WeKit ships as its `legacy` flavour, and it is the path that works
    // on frameworks where the modern libxposed entry point does not.

    implementation("org.luckypray:dexkit:2.3.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.annotation:annotation:1.9.1")
    // v2.5.0: FileProvider, for the log share sheet
    implementation("androidx.core:core-ktx:1.13.1")

    testImplementation("junit:junit:4.13.2")
    // Android 鐨?org.json 鍦?JVM 鍗曟祴閲屾槸绌哄３锛岀敤鐪熷疄瀹炵幇鏇挎崲浠ヤ究娴嬭瘯 JSON 瑙ｆ瀽
    testImplementation("org.json:json:20240303")
}
