package com.luoliaoo.wechat.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import com.luoliaoo.wechat.core.AppContextHolder
import com.luoliaoo.wechat.core.LegacyHook
import com.luoliaoo.wechat.core.Log
import java.io.ByteArrayOutputStream
import java.lang.reflect.Method

/**
 * Picks ONE file through the SYSTEM file chooser (Storage Access Framework).
 *
 * WHY THIS REPLACED THE OLD SCANNER: the module used to find an import zip by scanning `Download`,
 * `Documents` and its own directories with `File.listFiles()`. On Android 11+ (scoped storage) listing the
 * public directories returns NOTHING for an app -- it may only list its OWN files -- so a zip the user had
 * downloaded was invisible and the import reported "没有找到可导入的文件" while the file sat right there.
 * That was the import bug.
 *
 * The system chooser has none of that: the user picks a file from anywhere (下载 / 微信收到的文件 / 网盘 /
 * U 盘), the framework hands back a read URI for exactly that file, and no permission is needed on any
 * Android version.
 *
 * HOW THE RESULT COMES BACK: the module's UI lives inside WeChat's process and owns no Activity, so the
 * usual `onActivityResult` override is impossible -- there is nothing of ours to override it in. The
 * receiving Activity's result methods are therefore hooked for the duration of one pick:
 *
 *   * the CONCRETE runtime class is walked from most-derived upward, because hooking
 *     `Activity.onActivityResult` alone would MISS a WeChat subclass that overrides it;
 *   * both `dispatchActivityResult` (the framework's own funnel, called directly by ActivityThread) and
 *     `onActivityResult` (the public callback) are covered, so either path delivers;
 *   * the hook only READS. Every request code that is not ours is ignored completely and the original
 *     method always runs, so WeChat's own results are untouched.
 *
 * NOTE: comments are ASCII-only on purpose (see core/Log.kt).
 */
object FilePicker {

    private const val TAG = "LuoLiao"

    /** A request code unlikely to collide with WeChat's own (which use small values). */
    private const val REQ_PICK = 0x4C4C10

    /** The largest file read into memory, so picking a 2 GB video cannot kill the process. */
    private const val MAX_BYTES = 8L * 1024 * 1024

    /** What the user picked: the display name and the raw bytes. */
    class Picked(val name: String, val bytes: ByteArray)

    /** Why a pick failed, ready to show. */
    class Failure(val message: String)

    /** Methods already hooked, keyed by class+name+arity, so a second pick does not stack callbacks. */
    private val hookedMethods = HashSet<String>()

    @Volatile
    private var pending: ((Any) -> Unit)? = null

    /**
     * Opens the system file chooser and delivers the bytes.
     *
     * The callback runs with either a [Picked] or a [Failure] -- never null, so a caller has exactly one
     * place to handle both outcomes.
     */
    fun pick(ctx: Context, onResult: (Any) -> Unit) {
        val activity = activityOf(ctx)
        if (activity == null) {
            // 5.0.0: FilePicker is only reached from SkillDialog, which runs INSIDE the host process,
            // so HostApp.say resolves the running host rather than assuming WeChat.
            onResult(Failure(com.luoliaoo.wechat.core.HostApp.say(
                "找不到可用的界面，请回到微信聊天页后重试",
            )))
            return
        }
        if (!hookResults(activity)) {
            onResult(Failure(com.luoliaoo.wechat.core.HostApp.say(
                "无法接入系统文件选择器，请重启微信后重试",
            )))
            return
        }

        pending = onResult

        // No MIME filter is set on purpose. A zip from some providers is reported as
        // application/octet-stream, and a filter that is slightly off would GREY OUT the very file the user
        // wants to pick. Everything stays selectable and the content is validated after the pick instead --
        // a wrong choice produces a clear message, never a silently hidden file.
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            /* v10.2.0：申请「可持久化」的读授权。
             *
             * 系统只有在发起时带上这个标志，返回后才允许我们对 URI 调
             * takePersistableUriPermission（见 hookResults 里的处理）。
             * 少了它，即便调了持久化也会抛 SecurityException，退化成"只在返回瞬间可读"，
             * 而那正是「无法打开所选文件」的一类成因。
             *
             * 注意：这个标志不会扩大权限范围 —— 用户依然是通过系统选择器显式选中了这个文件，
             * 我们只是把「这一次选中」的读取权保留下来，而不是获得任何目录访问能力。 */
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        try {
            activity.startActivityForResult(intent, REQ_PICK)
            Log.i(TAG, "system file picker opened")
        } catch (e: ActivityNotFoundException) {
            // The modern chooser is missing on a few ROMs; the older one is still shipped everywhere.
            Log.w(TAG, "ACTION_OPEN_DOCUMENT unavailable, trying ACTION_GET_CONTENT")
            try {
                val legacy = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                activity.startActivityForResult(legacy, REQ_PICK)
            } catch (t: Throwable) {
                pending = null
                Log.w(TAG, "no file chooser available: " + t.message)
                onResult(Failure("这台设备没有可用的文件选择器"))
            }
        } catch (t: Throwable) {
            pending = null
            Log.w(TAG, "opening the file picker failed: " + t.message)
            onResult(Failure("无法打开文件选择器：" + (t.message ?: "未知错误")))
        }
    }

    /**
     * Hooks the result methods of the CONCRETE Activity that will receive the pick.
     *
     * Walking the class hierarchy from the runtime class upward is what makes this reliable: the FIRST
     * declaration found for each name is the most derived one, i.e. exactly the method the framework will
     * dispatch to. Hooking `Activity.onActivityResult` alone would miss a subclass override, which is the
     * mistake that makes a "hooked" picker never call back.
     *
     * @return false when nothing could be hooked, in which case the result could never arrive and the
     *         caller must not pretend a chooser was opened.
     */
    @Synchronized
    private fun hookResults(activity: Activity): Boolean {
        var installed = 0
        for (m in resultMethodsOf(activity.javaClass)) {
            val key = m.declaringClass.name + "#" + m.name + "/" + m.parameterTypes.size
            if (hookedMethods.contains(key)) {
                installed++
                continue
            }
            try {
                LegacyHook.hook(m)
                    .setExceptionMode(LegacyHook.Frame.EXCEPTION_MODE)
                    .intercept { chain ->
                        // The two signatures disagree on where the Intent sits, so the request code is
                        // found BY TYPE. Reading by index would eventually read an Intent as an int.
                        val ints = chain.args.filterIsInstance<Int>()
                        // dispatchActivityResult(String who, int requestCode, ...) puts the code first in
                        // practice for both, and it is the only int that is ever REQ_PICK.
                        if (ints.isNotEmpty() && ints[0] == REQ_PICK) {
                            val code = ints.getOrNull(1) ?: 0
                            val data = chain.args.filterIsInstance<Intent>().firstOrNull()
                            /* v10.2.0：在**读之前**把授权持久化。
                             *
                             * ACTION_OPEN_DOCUMENT 与老的 GET_CONTENT 的关键区别，就是它给的是
                             * **可持久化**的授权：只要调一次 takePersistableUriPermission，
                             * 这个 URI 就归我们（=宿主包）长期可读，不再依赖"选择器返回的那一瞬间"。
                             *
                             * 【为什么必须在这里做】用户报的「无法打开所选文件」有一类原因，
                             * 就是授权只在返回瞬间有效、而我们真正去读时已经过期（或跨了一次转发）。
                             * 先持久化能直接消掉这一类，代价只有一次 IPC。
                             *
                             * 用 READ 标志，且**必须 try 包住**：不是所有 provider 都支持持久化
                             * （返回的 URI 若不带 FLAG_GRANT_PERSISTABLE_URI_PERMISSION 会抛
                             * SecurityException），那种情况下这只是一个无副作用的失败，
                             * 不能因此让整个选取流程崩掉。 */
                            if (code == Activity.RESULT_OK && data?.data != null) {
                                try {
                                    (chain.thisObject as? Context)?.contentResolver
                                        ?.takePersistableUriPermission(
                                            data.data!!,
                                            Intent.FLAG_GRANT_READ_URI_PERMISSION,
                                        )
                                    Log.i(TAG, "persisted the read grant for the picked uri")
                                } catch (t: Throwable) {
                                    // 不支持持久化是正常的，下面仍会按普通授权读取。
                                    Log.i(TAG, "takePersistableUriPermission not available: " + t.message)
                                }
                            }
                            // The receiving Activity IS the Context the URI grant belongs to, so it is the
                            // one that can actually open the stream.
                            onPicked(chain.thisObject as? Context, code, data)
                        }
                        // Nothing is skipped and nothing is written: WeChat's own results pass through
                        // completely untouched.
                    }
                hookedMethods.add(key)
                installed++
            } catch (t: Throwable) {
                Log.w(TAG, "hooking " + key + " failed: " + t.message)
            }
        }
        if (installed > 0) {
            Log.i(TAG, "file picker result hook ready (" + installed + " method(s))")
        } else {
            Log.w(TAG, "no Activity result method could be hooked on " + activity.javaClass.name)
        }
        return installed > 0
    }

    /** The most-derived declarations of the Activity result funnels, walking up from the runtime class. */
    private fun resultMethodsOf(cls: Class<*>): List<Method> {
        val found = LinkedHashMap<String, Method>()
        var c: Class<*>? = cls
        var depth = 0
        while (c != null && c != Any::class.java && depth < 30) {
            for (m in c.declaredMethods) {
                val name = m.name
                if (name != "onActivityResult" && name != "dispatchActivityResult") continue
                if (m.isSynthetic || m.isBridge) continue
                val p = m.parameterTypes
                // requestCode is always the FIRST int parameter of these two methods.
                if (p.isEmpty() || p[0] != Int::class.javaPrimitiveType) continue
                val key = name + "/" + p.size
                // Walking from the concrete class upward means the first hit is the most derived one.
                if (!found.containsKey(key)) found[key] = m
            }
            c = c.superclass
            depth++
        }
        return found.values.toList()
    }

    /** Runs once the chooser closes. `code` is RESULT_OK / RESULT_CANCELED. */
    private fun onPicked(host: Context?, code: Int, data: Intent?) {
        val cb = pending
        pending = null
        if (cb == null) return

        if (code != Activity.RESULT_OK) {
            // A cancelled picker is a normal outcome, not an error: say so plainly and change nothing.
            Log.i(TAG, "file pick cancelled (resultCode=" + code + ")")
            cb(Failure("已取消"))
            return
        }
        val uri: Uri? = data?.data
        if (uri == null) {
            cb(Failure("没有取到文件（返回数据为空）"))
            return
        }
        try {
            cb(read(host, uri))
        } catch (t: Throwable) {
            Log.w(TAG, "reading the picked file failed: " + t.message)
            cb(Failure("读取失败：" + (t.message ?: "无法打开所选文件")))
        }
    }

    /** Reads the picked URI into memory, with the display name the system reports. */
    private fun read(host: Context?, uri: Uri): Any {
        val ctx = host
            ?: AppContextHolder.get()
            ?: return Failure(com.luoliaoo.wechat.core.HostApp.say(
                "读取失败：应用上下文不可用，请重启微信后重试",
            ))

        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "picked.zip"
        var declaredSize = -1L
        try {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (ni >= 0 && !c.isNull(ni)) c.getString(ni)?.let { if (it.isNotBlank()) name = it }
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (si >= 0 && !c.isNull(si)) declaredSize = c.getLong(si)
                }
            }
        } catch (t: Throwable) {
            // A provider that refuses the query is fine: the name falls back to the URI and the size is
            // then enforced while reading.
            Log.w(TAG, "querying the picked file failed: " + t.message)
        }

        if (declaredSize > MAX_BYTES) {
            return Failure("文件过大（" + mb(declaredSize) + "，上限 " + mb(MAX_BYTES) + "）")
        }

        /* ======================================================================
         * v10.2.0 修复：「读取失败：无法打开所选文件」
         *
         * 【用户报的现象】从「模板管理 → 导入」选完 zip 后弹「读取失败：无法打开所选文件」。
         *
         * 【根因】原来这里只有一条路：`ctx.contentResolver.openInputStream(uri)`。
         * 它失败时返回 null，代码直接吞掉（连异常都没记），只在最后给一句笼统的提示 ——
         * 所以从日志里**看不出到底为什么失败**。而这里失败的原因有多种，性质完全不同：
         *
         *   a) **URI 授权没落到我们读的那个 Context 上**。这是个 LSPosed 模块，
         *      我们的界面跑在**微信进程**里、用的是微信的 Activity。系统给的选择器
         *      （DocumentsUI / 各家文件管理）把 `FLAG_GRANT_READ_URI_PERMISSION` 授予的是
         *      **发起 startActivityForResult 的那个 Activity 所属的包**。正常情况就是微信，
         *      所以能读；但部分 ROM 的文件管理器返回的是**它自己 provider 的 URI**
         *      （如 `content://com.android.externalstorage.documents/...`），
         *      这种 URI 在跨进程二次转发后就可能不再可读。
         *   b) 某些 provider 的 `openInputStream` 对 `content://` 之外的 scheme 直接返回 null。
         *   c) 部分 ROM 会在选择器返回后立刻回收临时授权。
         *
         * 【修法】按可靠性依次尝试多条读取路径，并把**每一条失败的原因都记进日志**
         * （这是本次最重要的改动：即便全部失败，下次也能从日志直接看出是哪一类）。
         *
         *   ① contentResolver.openInputStream(uri)   —— 最标准，正常情况走这条；
         *   ② 用 URI 的 file:// 路径直接读          —— 有些文件管理器返回 file:// URI；
         *   ③ 从 `_data` 列拿真实路径再按文件读     —— 老式 provider 仍然暴露这一列。
         *
         * 【为什么保留 ① 在第一位】它是最标准也最安全的一条；后两条是兜底，
         * 只在 ① 真的拿不到时才走，不会改变正常情况下的行为。
         * ====================================================================== */
        val stream = openPickedStream(ctx, uri)
        if (stream == null) {
            Log.w(TAG, "all read strategies failed for uri=" + uri)
            return Failure("读取失败：无法打开所选文件（URI=" + uri.scheme + "）")
        }

        val bytes: ByteArray? = try {
            stream.use { ins ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(8192)
                var total = 0L
                var over = false
                while (true) {
                    val n = ins.read(buf)
                    if (n <= 0) break
                    total += n
                    // Enforced WHILE reading too: a provider may not report a size, and the cap must hold
                    // either way.
                    if (total > MAX_BYTES) {
                        over = true
                        break
                    }
                    out.write(buf, 0, n)
                }
                if (over) null else out.toByteArray()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "reading the stream failed: " + t.message)
            null
        }
        if (bytes == null) return Failure("读取失败或文件过大（上限 " + mb(MAX_BYTES) + "）")
        if (bytes.isEmpty()) return Failure("文件是空的")
        Log.i(TAG, "picked file: " + name + " (" + bytes.size + " bytes)")
        return Picked(name, bytes)
    }

    /** Walks a Context chain to the Activity, which is what startActivityForResult needs. */
    private fun activityOf(ctx: Context?): Activity? {
        var c = ctx
        var d = 0
        while (c is ContextWrapper && d < 12) {
            if (c is Activity) return c
            c = c.baseContext
            d++
        }
        return null
    }

    /**
     * v10.2.0: 按可靠性依次尝试多条路径，打开用户选中的文件；全部失败返回 null。
     *
     * 每一条失败都写日志（含具体异常），这样下次出问题能直接从日志定位是哪一类原因 ——
     * 原来的实现把异常吞掉，只留一句「无法打开所选文件」，无法排查。
     */
    private fun openPickedStream(ctx: Context, uri: Uri): java.io.InputStream? {
        // ① 标准路径：ContentResolver 打开。绝大多数情况走这条就成功了。
        try {
            ctx.contentResolver.openInputStream(uri)?.let {
                Log.i(TAG, "picked file opened via contentResolver")
                return it
            }
            Log.w(TAG, "contentResolver returned null for uri=" + uri)
        } catch (t: Throwable) {
            // SecurityException 是"授权没落到这个 Context"的典型信号，单独点出来便于判断。
            Log.w(TAG, "contentResolver.openInputStream failed: " + t.javaClass.simpleName + ": " + t.message)
        }

        // ② file:// URI 直接按路径读。有些文件管理器返回的就是 file scheme，
        //    对它们走 ContentResolver 反而可能拿不到。
        try {
            if ("file" == uri.scheme) {
                val p = uri.path
                if (!p.isNullOrBlank()) {
                    val f = java.io.File(p)
                    if (f.isFile) {
                        Log.i(TAG, "picked file opened as a direct file path")
                        return java.io.FileInputStream(f)
                    }
                    Log.w(TAG, "file uri path is not a readable file: " + p)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "direct file read failed: " + t.javaClass.simpleName + ": " + t.message)
        }

        // ③ 老式 provider 仍然可能在 `_data` 列里给出真实路径 —— 拿它再按文件读一次。
        //    这条在现代 Android 上越来越少有效，所以排在最后；它失败是完全正常的。
        try {
            ctx.contentResolver.query(uri, arrayOf("_data"), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val i = c.getColumnIndex("_data")
                    if (i >= 0 && !c.isNull(i)) {
                        val p = c.getString(i)
                        if (!p.isNullOrBlank()) {
                            val f = java.io.File(p)
                            if (f.isFile) {
                                Log.i(TAG, "picked file opened via the _data column")
                                return java.io.FileInputStream(f)
                            }
                        }
                    }
                }
            }
            Log.w(TAG, "no usable _data column for uri=" + uri)
        } catch (t: Throwable) {
            Log.w(TAG, "_data fallback failed: " + t.javaClass.simpleName + ": " + t.message)
        }

        return null
    }

    private fun mb(bytes: Long): String = String.format("%.1f MB", bytes / 1024.0 / 1024.0)

    /** A short toast, for an outcome that should be visible without a dialog. */
    fun toast(ctx: Context, msg: String) {
        try {
            Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
        } catch (_: Throwable) {
            // no UI thread
        }
    }
}
