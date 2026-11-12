package com.luoliaoo.wechat.core

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Packages everything needed to diagnose a problem into ONE zip.
 *
 * The CONTENT is deliberately small and complete:
 *   * the module's own debug log (this process)
 *   * any log files left by the WeChat process
 *   * an environment summary: android user, pid, versions, every relevant path, and the config
 *   * the stored data (templates, selected template) so a data problem is visible
 *
 * WHY: each troubleshooting round used to need the user to export the LSPosed log, which is large and
 * includes unrelated modules. This produces one file, named with the time and the android user, so several
 * are distinguishable.
 */
object LogPacker {

    private const val TAG = "LuoLiao"

    private val BEIJING = java.util.TimeZone.getTimeZone("Asia/Shanghai")
    private val SDF = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA).apply { timeZone = BEIJING }

    /** Writes the zip and returns its absolute path, or null with the reason logged. */
    fun pack(ctx: Context, extra: Map<String, String> = emptyMap()): String? {
        val user = android.os.Process.myUid() / 100000
        val name = "luoliao-log-" + SDF.format(Date()) + "-user" + user + ".zip"

        val dir = try {
            ctx.getExternalFilesDir(null) ?: ctx.filesDir
        } catch (_: Throwable) {
            ctx.filesDir
        }
        val out = File(dir, name)

        return try {
            out.parentFile?.mkdirs()
            ZipOutputStream(out.outputStream().buffered()).use { zip ->
                // ---- the module's own log
                val snap = DebugLog.snapshot()
                put(zip, "luoliao-debug-" + user + ".log", snap.joinToString("\n"))

                // ---- the log file, if one exists on disk
                DebugLog.file()?.takeIf { it.isFile }?.let { f ->
                    put(zip, "luoliao-debug-file-" + user + ".log", f.readText())
                }

                // ---- FIX: the WECHAT-SIDE log.
                //
                // The module's app and the WeChat process are different packages, so they write their logs to
                // DIFFERENT external directories:
                //     .../com.luoliaoo.wechat/files/luoliao-debug.log   <- the app
                //     .../com.tencent.mm/files/luoliao-debug.log        <- WeChat, the one that explains a bug
                // The old code only looked inside OUR own directory, which is why the packaged log never
                // contained anything from WeChat. Both are on the same external storage and both are
                // readable, so every one is collected here.
                val logDirs = ArrayList<File>(4)
                try {
                    ctx.getExternalFilesDir(null)?.let { logDirs.add(it) }
                } catch (_: Throwable) {
                    // ignore
                }
                try {
                    logDirs.add(ctx.filesDir)
                } catch (_: Throwable) {
                    // ignore
                }
                // The WeChat package's external dirs, for the android user we are running as.
                val user = android.os.Process.myUid() / 100000
                val userBase = if (user == 0) {
                    "/storage/emulated/0"
                } else {
                    "/storage/emulated/" + user
                }
                for (pkg in arrayOf("com.tencent.mm", com.luoliaoo.wechat.BuildConfig.APPLICATION_ID)) {
                    logDirs.add(File(userBase + "/Android/data/" + pkg + "/files"))
                }
                // ...and the internal dirs, which a rooted setup may still let us read.
                for (pkg in arrayOf("com.tencent.mm", com.luoliaoo.wechat.BuildConfig.APPLICATION_ID)) {
                    logDirs.add(File("/data/user/" + user + "/" + pkg + "/files"))
                }

                var logsFound = 0
                for (d in logDirs) {
                    try {
                        if (!d.isDirectory) continue
                        d.listFiles()?.forEach { f ->
                            if (f.isFile && f.name.startsWith("luoliao-debug") &&
                                f.extension == "log" && f.length() > 0
                            ) {
                                val safe = d.absolutePath
                                    .replace("/storage/emulated/", "sdcard-user-")
                                    .replace("/data/user/", "internal-user-")
                                    .replace("/", "_")
                                put(zip, "logs/" + safe + "_" + f.name, f.readText())
                                logsFound++
                            }
                        }
                    } catch (_: Throwable) {
                        // a directory we cannot read simply contributes nothing
                    }
                }
                Log.i(TAG, "log packaged: collected " + logsFound + " log file(s) from " + logDirs.size +
                    " director(ies)")

                // ---- the environment summary
                put(zip, "environment.txt", environment(ctx, user, extra))

                // ---- the stored data
                try {
                    val tdir = File(ctx.filesDir, "luoliao/templates")
                    tdir.listFiles()?.forEach { f ->
                        if (f.isFile) put(zip, "data/templates/" + f.name, f.readText())
                    }
                } catch (_: Throwable) {
                    // best effort
                }
                try {
                    val sel = File(ctx.filesDir, "luoliao/template_selected.txt")
                    if (sel.isFile) put(zip, "data/template_selected.txt", sel.readText())
                } catch (_: Throwable) {
                    // best effort
                }

                // ---- a short README so the file explains itself
                put(
                    zip,
                    "README.txt",
                    "落聊诊断日志\n" +
                        "生成时间：" + SDF.format(Date()) + "（北京时间）\n" +
                        "Android 用户：" + user + "\n" +
                        "把整个 zip 发给开发者即可。\n",
                )
            }
            Log.i(TAG, "log packaged -> " + out.absolutePath)
            out.absolutePath
        } catch (t: Throwable) {
            Log.e(TAG, "packaging the log failed: " + t.message)
            null
        }
    }

    /** The external storage root for this android user, used for the WeChat-side log path. */
    private fun userBaseFor(user: Int): String =
        if (user == 0) "/storage/emulated/0" else "/storage/emulated/" + user

    private fun put(zip: ZipOutputStream, entryName: String, text: String) {
        try {
            zip.putNextEntry(ZipEntry(entryName))
            zip.write(text.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        } catch (_: Throwable) {
            // a single entry must not abort the archive
        }
    }

    /**
     * The facts that decide almost every diagnosis: the android USER, the pid, the versions, and the paths.
     * This is what repeatedly had to be inferred from a huge log.
     */
    private fun environment(ctx: Context, user: Int, extra: Map<String, String>): String {
        val sb = StringBuilder()
        sb.append("=== 落聊环境信息 ===\n")
        sb.append("androidUser=").append(user).append('\n')
        sb.append("uid=").append(android.os.Process.myUid()).append('\n')
        sb.append("pid=").append(android.os.Process.myPid()).append('\n')
        sb.append("sdk=").append(android.os.Build.VERSION.SDK_INT).append('\n')
        sb.append("device=").append(android.os.Build.MANUFACTURER).append(' ')
            .append(android.os.Build.MODEL).append('\n')
        sb.append("fontScale=").append(ctx.resources.configuration.fontScale).append('\n')
        sb.append("moduleVersion=").append(com.luoliaoo.wechat.BuildInfo.VERSION_NAME).append('\n')
        sb.append("moduleBuild=").append(com.luoliaoo.wechat.BuildInfo.BUILD_ID).append('\n')
        sb.append('\n')
        sb.append("=== 路径 ===\n")
        sb.append("module filesDir=").append(ctx.filesDir.absolutePath).append('\n')
        sb.append("module externalFilesDir=")
            .append(try { ctx.getExternalFilesDir(null)?.absolutePath ?: "(none)" } catch (_: Throwable) { "(error)" })
            .append('\n')
        sb.append("debug log file=").append(DebugLog.file()?.absolutePath ?: "(none)").append('\n')
        sb.append("weChat log expected at=")
            .append(userBaseFor(user)).append("/com.tencent.mm/files/luoliao-debug.log").append('\n')
        sb.append('\n')
        sb.append("=== 运行时状态 ===\n")
        sb.append("debugLogEnabled=").append(DebugLog.isEnabled()).append('\n')
        sb.append("adaptersSeen=").append(ModuleStats.adapters).append('\n')
        sb.append("balloonsSeen=").append(ModuleStats.balloons).append('\n')
        sb.append("heartbeats=").append(ModuleStats.heartbeats).append('\n')
        sb.append("inWeChatProcess=").append(ModuleBridge.inWeChatProcess).append('\n')
        sb.append("hasContext=").append(ModuleBridge.hasContext).append('\n')
        try {
            sb.append("workDir=").append(ModuleBridge.workDir.absolutePath).append('\n')
        } catch (_: Throwable) {
            sb.append("workDir=(unavailable)\n")
        }
        sb.append("appContext=").append(ModuleBridge.appContext?.packageName ?: "(none)").append('\n')
        sb.append('\n')
        sb.append("=== 配置 ===\n")
        try {
            val cfg = com.luoliaoo.wechat.data.ConfigRepo.loadLocal(ctx)
            sb.append("baseUrl=").append(cfg.baseUrl).append('\n')
            sb.append("model=").append(cfg.model).append('\n')
            sb.append("keyPresent=").append(cfg.apiKey.isNotBlank()).append('\n')
            sb.append("adaptiveFont=").append(cfg.adaptiveFont).append('\n')
            sb.append("debugLog=").append(cfg.debugLog).append('\n')
            sb.append("scope=").append(cfg.defaultUp).append('/').append(cfg.defaultDown)
                .append('/').append(cfg.defaultRangeHours).append('\n')
        } catch (t: Throwable) {
            sb.append("config unreadable: ").append(t.message).append('\n')
        }
        try {
            sb.append("templates=")
                .append(com.luoliaoo.wechat.data.TemplateStore.all().joinToString { it.name })
                .append('\n')
            sb.append("selectedTemplate=")
                .append(com.luoliaoo.wechat.data.TemplateStore.selectedName()).append('\n')
        } catch (t: Throwable) {
            sb.append("templates unreadable: ").append(t.message).append('\n')
        }
        if (extra.isNotEmpty()) {
            sb.append('\n')
            sb.append("=== 附加信息 ===\n")
            for ((k, v) in extra) sb.append(k).append('=').append(v).append('\n')
        }
        return sb.toString()
    }
}
