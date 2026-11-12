package com.luoliaoo.wechat.core

import android.os.Process
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

/**
 * Loader for the DexKit native library.
 *
 * PROBLEM: DexKit is a C++ library (System.loadLibrary("dexkit")), but the module
 * runs INSIDE the WeChat process, so System.loadLibrary would search WeChat's lib
 * directory while libdexkit.so ships in OUR module APK.
 *
 * CRASH FIX (v1.0.9): the extracted file name is now PROCESS-UNIQUE.
 * Previously every WeChat process extracted to the same path
 * (files/luoliao/dexkit_lib/libdexkit.so). Two processes writing that file at once
 * corrupted it, and loading the corrupt library produced
 *     Fatal signal 11 (SIGSEGV) in tid (LuoLiao-install) pid (tencent.mm:push)
 * The extraction is also written to a temp file and atomically renamed, so a
 * half-written .so can never be loaded.
 */
object SoLoader {

    private const val TAG = "LuoLiao"
    private const val LIB_NAME = "libdexkit.so"

    @Volatile
    private var loaded = false

    @Volatile
    private var failed: String? = null

    fun ensureLoaded(moduleInfo: android.content.pm.ApplicationInfo?, workDir: File): Boolean {
        if (loaded) return true
        if (failed != null) return false
        if (moduleInfo == null) {
            failed = "module ApplicationInfo unavailable"
            Log.w(TAG, "dexkit not loaded: " + failed)
            return false
        }

        synchronized(this) {
            if (loaded) return true
            if (failed != null) return false

            Log.i(
                TAG,
                "loading dexkit; user=" + android.os.Process.myUid() / 100000 +
                    " nativeLibraryDir=" + moduleInfo.nativeLibraryDir +
                    " sourceDir=" + moduleInfo.sourceDir,
            )

            // SOURCE 1 -- loadLibrary FIRST, and this ordering matters.
            //
            // THE CLONE BUG: a clone runs as another android user (999). The module's nativeLibraryDir points
            // at /data/app/... owned by user 0, where `libdexkit.so` does NOT exist (a previous log shows
            // `exists=false len=0`). Attempting `System.load` on a path from another user's data directory is
            // a native-level failure that can kill the calling THREAD without an exception reaching Kotlin --
            // which is exactly what the newest log shows: 'dexKitResolve: entering' followed by nothing at all,
            // while the UI thread keeps running. The install therefore never finishes and no menu hook exists.
            //
            // In v1.0.65 the clone succeeded through `System.loadLibrary`, so that is tried FIRST now. It asks
            // the framework to resolve the library from the APK the process actually belongs to, which is
            // correct in every user.
            val user = android.os.Process.myUid() / 100000
            if (user != 0) {
                Log.i(TAG, "dexkit: clone detected (user=" + user + "); loadLibrary first")
            }
            try {
                System.loadLibrary("dexkit")
                loaded = true
                Log.i(TAG, "dexkit loaded via System.loadLibrary (user=" + user + ")")
                return true
            } catch (t: Throwable) {
                Log.i(
                    TAG,
                    "dexkit loadLibrary failed: " + t.javaClass.simpleName + ": " + t.message +
                        " -- the library is not on THIS process's search path, so an extracted file is used",
                )
            }

            // SOURCE 2: the module's own native library directory, which is present in user 0.
            val libFile = File(moduleInfo.nativeLibraryDir, LIB_NAME)
            Log.i(TAG, "dexkit source 2: " + libFile.absolutePath +
                " exists=" + libFile.isFile + " len=" + libFile.length() +
                " readable=" + libFile.canRead())
            if (libFile.isFile && libFile.length() > 0L) {
                val fromLibDir = tryLoad(libFile)
                if (fromLibDir != null) {
                    loaded = true
                    Log.i(TAG, "dexkit loaded from nativeLibraryDir: " + fromLibDir)
                    return true
                }
            } else {
                // Do NOT call System.load on a path that does not exist; a native failure here is what
                // silently killed the install worker in the clone.
                Log.i(TAG, "dexkit source 2 skipped: the file is absent or empty")
            }

            val candidates = ArrayList<String>()
            moduleInfo.sourceDir?.let { candidates.add(it) }
            moduleInfo.splitSourceDirs?.let { arr ->
                for (s in arr) if (s != null) candidates.add(s)
            }
            // SOURCE 3: the module APK as seen through the MODULE's own context. This is the path that is
            // reachable in every Android user, which is exactly what the clone needs.
            try {
                val moduleCtx = ModuleBridge.moduleContext
                val info = moduleCtx?.applicationInfo
                info?.sourceDir?.let { if (!candidates.contains(it)) candidates.add(it) }
                Log.i(TAG, "dexkit source 3 candidate: " + info?.sourceDir)
            } catch (t: Throwable) {
                Log.w(TAG, "dexkit source 3 unavailable: " + t.message)
            }

            for (apk in candidates) {
                val f = File(apk)
                Log.i(TAG, "dexkit extracting from " + apk +
                    " exists=" + f.isFile + " readable=" + f.canRead())
                val extracted = tryExtract(f, workDir) ?: continue
                Log.i(TAG, "dexkit extracted to " + extracted.absolutePath +
                    " len=" + extracted.length())
                val ok = tryLoad(extracted)
                if (ok != null) {
                    loaded = true
                    Log.i(TAG, "dexkit loaded after extraction from " + apk)
                    return true
                }
            }

            failed = "could not load " + LIB_NAME
            Log.w(TAG, "DEXKIT UNAVAILABLE in user " + android.os.Process.myUid() / 100000 +
                ": " + failed + " -- continuing WITHOUT DexKit (menu resolution uses the version table)")
            return false
        }
    }

    private fun tryLoad(so: File): String? {
        // Every fact about the file, BEFORE the load. Nuke's success is visible as one nativeloader line;
        // this is the equivalent forward evidence, so a failure says WHICH condition was not met.
        val info = "isFile=" + so.isFile + " len=" + so.length() +
            " read=" + so.canRead() + " exec=" + so.canExecute() +
            " path=" + so.absolutePath
        if (!so.isFile || so.length() == 0L) {
            Log.i(TAG, "tryLoad: unusable -> " + info)
            return null
        }
        return try {
            // System.load on the ABSOLUTE PATH is the file-based call: it is what makes the framework print
            // `nativeloader: Load <path> ... : ok`, which is the line Nuke gets and mine never did.
            Log.i(TAG, "tryLoad: System.load -> " + info)
            System.load(so.absolutePath)
            Log.i(TAG, "tryLoad: LOADED OK -> " + so.absolutePath)
            so.absolutePath
        } catch (t: Throwable) {
            Log.w(
                TAG,
                "tryLoad: System.load FAILED -> " + so.absolutePath +
                    " : " + t.javaClass.simpleName + ": " + t.message + " (" + info + ")",
            )
            null
        }
    }

    /**
     * Extracts libdexkit.so for THIS process only, writing to a temp file and then
     * renaming so no other process can observe a partial write.
     */
    private fun tryExtract(apk: File, workDir: File): File? {
        if (!apk.isFile) return null
        val pid = Process.myPid()
        // The name is derived from the CONTENT below, so a new build cannot reuse an old file. Until the
        // hash is known a per-pid name is used as the temporary.
        val uniqueName = "libdexkit_$pid.so"
        return try {
            val outDir = File(workDir, "dexkit_lib").apply { mkdirs() }
            val out = File(outDir, uniqueName)
            if (out.isFile && out.length() > 0L) return out   // already extracted by us

            ZipFile(apk).use { zip ->
                var entry: java.util.zip.ZipEntry? = null
                for (abi in android.os.Build.SUPPORTED_ABIS) {
                    entry = zip.getEntry("lib/" + abi + "/" + LIB_NAME)
                    if (entry != null) break
                }
                if (entry == null) {
                    val it = zip.entries()
                    while (it.hasMoreElements()) {
                        val e = it.nextElement()
                        if (e.name.endsWith("/" + LIB_NAME)) {
                            entry = e
                            break
                        }
                    }
                }
                if (entry == null) return null

                // A TEMP file first: a half-written library must never be loaded.
                val tmp = File(outDir, uniqueName + ".tmp")
                zip.getInputStream(entry).use { input ->
                    FileOutputStream(tmp).use { output -> input.copyTo(output) }
                }
                // NUKE'S TRICK: name the file by a HASH OF ITS CONTENT.
                //
                // A new build therefore extracts a NEW file instead of reusing a stale or corrupt one, and
                // two processes cannot collide. Nuke uses the same scheme
                // ("<sha>-libnuke_bridge.so").
                val hash = try {
                    val md = java.security.MessageDigest.getInstance("SHA-256")
                    tmp.inputStream().use { ins ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = ins.read(buf)
                            if (n <= 0) break
                            md.update(buf, 0, n)
                        }
                    }
                    md.digest().joinToString("") { "%02x".format(it) }.take(32)
                } catch (_: Throwable) {
                    "pid$pid"
                }
                val hashed = File(outDir, "libdexkit_$hash.so")
                if (hashed.isFile && hashed.length() == tmp.length()) {
                    tmp.delete()
                    Log.i(TAG, "dexkit: already extracted " + hashed.name)
                    return hashed
                }
                if (!tmp.renameTo(hashed)) {
                    hashed.delete()
                    if (!tmp.renameTo(hashed)) {
                        Log.w(TAG, "dexkit: could not publish the extracted library")
                        return null
                    }
                }
                Log.i(
                    TAG,
                    "dexkit: extracted to " + hashed.absolutePath +
                        " len=" + hashed.length() +
                        " exec=" + hashed.canExecute() + " read=" + hashed.canRead(),
                )
                return hashed
                // Atomic-ish publish: never load a partially written file.
                if (out.exists()) out.delete()
                if (!tmp.renameTo(out)) {
                    tmp.copyTo(out, overwrite = true)
                    tmp.delete()
                }
                out.setReadable(true, false)
                out.setExecutable(true, false)
                out
            }
        } catch (t: Throwable) {
            Log.w(TAG, "extract " + LIB_NAME + " (pid " + pid + ") failed: " + t.message)
            null
        }
    }

    fun statusText(): String = when {
        loaded -> "loaded"
        failed != null -> "unavailable (" + failed + ")"
        else -> "not attempted"
    }
}
