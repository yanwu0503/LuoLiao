package com.luoliaoo.wechat.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.luoliaoo.wechat.core.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * API key storage.
 *
 * Two scopes:
 *   * saveLocal / loadLocal  -- for the process that actually uses the key (the WeChat
 *     process). Keystore is tried first; if the Keystore is unavailable or a saved value
 *     cannot be decrypted, the key is stored as plain text in the SAME app-private
 *     preferences. That fallback exists so a Keystore failure can never lock the user out
 *     of their own configuration -- the preferences are already private to the app/UID.
 *   * saveEncrypted / loadDecrypted -- the module app's encrypted archive.
 *
 * The two scopes use different Keystore aliases, because Keystore keys are bound to the app
 * UID and the module app and WeChat are different UIDs.
 */
object SecureStore {

    private const val TAG = "LuoLiao"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val ALIAS_MODULE = "luoliao_api_key_v1"
    private const val ALIAS_LOCAL = "luoliao_api_key_local_v1"
    private const val PREFS_MODULE = "luoliao_secure"
    private const val PREFS_LOCAL = "luoliao_secure_local"
    private const val FIELD_ENC = "api_key_enc"
    private const val FIELD_IV = "api_key_iv"
    private const val FIELD_PLAIN = "api_key_plain"
    private const val FIELD_MODE = "api_key_mode"
    private const val MODE_KEYSTORE = "keystore"
    private const val MODE_PLAIN = "plain"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128

    // ------------------------------------------------------------------ local

    /**
     * v3.1.0 性能修复：解密结果的内存缓存。
     *
     * 【问题】AndroidKeyStore 的每一次操作都是系统级 IPC：
     *     KeyStore.getInstance("AndroidKeyStore").load(null)   —— 打开 keystore
     *     Cipher.init(DECRYPT_MODE, key, ...) + doFinal()      —— 走 keystore daemon 解密
     * 单次典型耗时 50~300ms。而 ConfigRepo.loadLocal() 每次调用都会走到这里，
     * 悬浮球点击后的 render() 一次至少调用 2~3 次 loadLocal，于是累积出 1~2 秒的卡顿——
     * 这正是「点击悬浮球要卡一两秒才出现 UI」的直接原因。
     *
     * 【为什么可以缓存】API key 在两次保存之间不可能变化，缓存的是同一个值。
     * 唯一会改变它的路径是 saveLocal()，所以 saveLocal 里必须立即作废缓存。
     *
     * 【线程安全】用 @Volatile 持有，缓存的是不可变的 String，多线程读到旧值或新值都安全。
     * 这里刻意不加锁：加锁会让主线程去等一个正在解密的线程，反而更容易卡顿。
     */
    @Volatile
    private var localCache: String? = null

    /** Stores the key for THIS process. Keystore when possible, plain text otherwise. */
    fun saveLocal(ctx: Context, plain: String) {
        // t4 性能修复：API key 没变就完全不做任何事。
        //
        // 【为什么必须加这个判断】ConfigRepo.saveLocal() 会在保存【任意】字段时调用这里 ——
        // 悬浮球透明度拖动、面板宽度、自适应字体等等，全部都会走到这一行，而它们都不改 API key。
        // 原来的实现无条件执行「失效缓存 + getOrCreateKey(KeyStore.load(null) 系统 IPC) + AES/GCM 加密
        // + 写 prefs」，等于用户每拖一格透明度就白跑一次 keystore 往返（实测 50~300ms）。
        //
        // 这里比较的是【明文】：缓存命中时 loadLocal() 不碰 keystore，所以这个判断本身几乎免费。
        // 只有当明文真的变化（用户改了 key）才做加密。缓存已被读过则说明它就是当前磁盘上的值。
        if (localCache != null && localCache == plain) {
            // 值没变，磁盘上已经是这个值：缓存保持有效，直接返回。
            return
        }
        // 缓存未命中时无法断定磁盘上的值，必须走完整流程；同时先作废缓存，
        // 保证任何一次真正的写入之后，后续读取都会重新解密，绝不返回旧 key。
        localCache = null
        val p = ctx.getSharedPreferences(PREFS_LOCAL, Context.MODE_PRIVATE)
        if (plain.isEmpty()) {
            p.edit().clear().apply()
            // 空值也要缓存，否则「没有 key」的状态每次都要白跑一趟 keystore。
            localCache = ""
            return
        }
        try {
            val key = getOrCreateKey(ALIAS_LOCAL)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val enc = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            p.edit()
                .putString(FIELD_ENC, Base64.encodeToString(enc, Base64.NO_WRAP))
                .putString(FIELD_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .putString(FIELD_MODE, MODE_KEYSTORE)
                .remove(FIELD_PLAIN)
                .apply()
            // 刚加密完的值就是明文本身，直接填进缓存，省掉下一次注定相同的解密。
            localCache = plain
            Log.i(TAG, "saveLocal: stored with Keystore (len=" + plain.length + ")")
        } catch (t: Throwable) {
            Log.w(TAG, "saveLocal: Keystore unavailable, using plain text: " + t.message)
            p.edit()
                .putString(FIELD_PLAIN, plain)
                .putString(FIELD_MODE, MODE_PLAIN)
                .remove(FIELD_ENC)
                .remove(FIELD_IV)
                .apply()
            localCache = plain
        }
    }

    /** Reads the key stored by saveLocal for THIS process. Cached; see [localCache]. */
    fun loadLocal(ctx: Context): String {
        // 命中缓存时完全不碰 keystore —— 这是消除点击卡顿的关键路径。
        localCache?.let { return it }
        val value = readLocalUncached(ctx)
        localCache = value
        return value
    }

    private fun readLocalUncached(ctx: Context): String {
        return try {
            val p = ctx.getSharedPreferences(PREFS_LOCAL, Context.MODE_PRIVATE)
            when (p.getString(FIELD_MODE, null)) {
                MODE_KEYSTORE -> {
                    val encB64 = p.getString(FIELD_ENC, null) ?: return ""
                    val ivB64 = p.getString(FIELD_IV, null) ?: return ""
                    val key = getOrCreateKey(ALIAS_LOCAL)
                    val cipher = Cipher.getInstance(TRANSFORMATION)
                    cipher.init(
                        Cipher.DECRYPT_MODE, key,
                        GCMParameterSpec(GCM_TAG_BITS, Base64.decode(ivB64, Base64.NO_WRAP)),
                    )
                    String(cipher.doFinal(Base64.decode(encB64, Base64.NO_WRAP)), Charsets.UTF_8)
                }
                MODE_PLAIN -> p.getString(FIELD_PLAIN, "") ?: ""
                else -> ""
            }
        } catch (t: Throwable) {
            Log.w(TAG, "loadLocal failed: " + t.message)
            ""
        }
    }

    // ----------------------------------------------------------------- module

    fun saveEncrypted(ctx: Context, plain: String) {
        if (plain.isEmpty()) {
            ctx.getSharedPreferences(PREFS_MODULE, Context.MODE_PRIVATE).edit()
                .remove(FIELD_ENC).remove(FIELD_IV).apply()
            return
        }
        try {
            val key = getOrCreateKey(ALIAS_MODULE)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val enc = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            ctx.getSharedPreferences(PREFS_MODULE, Context.MODE_PRIVATE).edit()
                .putString(FIELD_ENC, Base64.encodeToString(enc, Base64.NO_WRAP))
                .putString(FIELD_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .apply()
        } catch (t: Throwable) {
            Log.w(TAG, "saveEncrypted failed: " + t.message)
        }
    }

    fun loadDecrypted(ctx: Context): String {
        return try {
            val p = ctx.getSharedPreferences(PREFS_MODULE, Context.MODE_PRIVATE)
            val encB64 = p.getString(FIELD_ENC, null) ?: return ""
            val ivB64 = p.getString(FIELD_IV, null) ?: return ""
            val key = getOrCreateKey(ALIAS_MODULE)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE, key,
                GCMParameterSpec(GCM_TAG_BITS, Base64.decode(ivB64, Base64.NO_WRAP)),
            )
            String(cipher.doFinal(Base64.decode(encB64, Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (t: Throwable) {
            Log.w(TAG, "loadDecrypted failed: " + t.message)
            ""
        }
    }

    private fun getOrCreateKey(alias: String): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }
}
