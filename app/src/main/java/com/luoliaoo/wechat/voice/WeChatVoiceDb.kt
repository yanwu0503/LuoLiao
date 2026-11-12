package com.luoliaoo.wechat.voice

import android.database.Cursor
import com.luoliaoo.wechat.core.Log
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.WeakHashMap

/**
 * v10.0.0: 复用**微信已经打开的** WCDB 句柄，读取语音转写结果。
 *
 * ══════════════════════════════════════════════════════════════════════════════════════
 * 【为什么必须"复用微信的句柄"，而不是自己 openDatabase】
 *
 * 本模块的 `data/DbReader.kt` 就是**反面教材**，它自己 `SQLiteDatabase.openDatabase` 打开
 * `EnMicroMsg.db`，结果 100% 失败：那个库是 SQLCipher 加密的，普通 SQLite 打开必然抛
 * `SQLiteDatabaseCorruptException "file is not a database"`（SQLITE_NOTADB）。
 * 用户日志实测过：`db located: .../EnMicroMsg.db size=2593465344`（2.5GB）之后立刻抛异常，
 * 全日志「db located」16 次、「db read failed」36 次 —— **零次成功**。
 *
 * 要自己打开就得自己解 SQLCipher 的密钥（从 imei+uin 推 md5 取前 7 位），这条路随微信版本
 * 变动、且需要读 imei；而**微信自己已经开好了这个库**，我们只要把那个句柄拿过来用即可。
 *
 * 做法：hook WCDB 的 `rawQuery` / `rawQueryWithFactory`，在**方法进入时**把 `thisObject`
 * （即已打开的 db 对象）弱引用存下来。此时它一定是打开的，且一定带正确的密钥。
 *
 * 【安全边界 —— 这是本文件最重要的约束】
 *   * 从不开库、从不关库、从不写库、从不读密钥；
 *   * 只走 `rawQuery(String, String[])` 这一条**只读**查询通道，且只 SELECT；
 *   * 句柄用 `WeakReference` 保存，最多留 4 个 —— 不阻止 GC，也不会把旧账号的库钉在内存里；
 *   * 每次查询前 `isOpen` 复核：库可能已被微信关掉，对已关闭的库调用会抛异常。
 * ══════════════════════════════════════════════════════════════════════════════════════
 */
object WeChatVoiceDb {

    private const val TAG = "LuoLiao"

    /** 微信主消息库的文件名。只要这一个，其它库（如公共库）不碰。 */
    private const val MAIN_DB = "EnMicroMsg.db"

    /** 最多同时保留几个句柄。多账号/多进程下同一个库可能有多个句柄，4 个足够用。 */
    private const val MAX_HANDLES = 4

    /** 语音消息的 type 值（微信 messages 表约定）。 */
    const val TYPE_VOICE = 34

    /** db 对象 -> 是否为主库，避免每次都反射 getPath()。 */
    private val known = WeakHashMap<Any, Boolean>()

    private val handles = ArrayList<WeakReference<Any>>()

    @Volatile
    private var installed = false

    /**
     * 安装句柄采集 hook。**由 WeChatHooks 在微信进程内调用一次**。
     *
     * 允许重复调用（幂等）：热重载或克隆环境下 install 可能被走到两次，
     * 重复 hook 会让同一次 rawQuery 触发两次 observe（只是多几次去重，不会错），但没必要。
     */
    fun install(loader: ClassLoader) {
        if (installed) return
        installed = true
        var hooks = 0
        // 两代 WCDB 的类名都试：compat 是旧封装的包名，database 是新版。
        // 用 Class.forName 而不是直接引用：这两个类在**模块进程**里不存在（只有微信进程有），
        // 直接引用会让模块自己的设置界面在加载本类时 NoClassDefFoundError。
        for (name in listOf(
            "com.tencent.wcdb.compat.SQLiteDatabase",
            "com.tencent.wcdb.database.SQLiteDatabase",
        )) {
            try {
                val clazz = Class.forName(name, false, loader)
                for (m in clazz.declaredMethods) {
                    if (m.name != "rawQuery" && m.name != "rawQueryWithFactory") continue
                    try {
                        XposedBridge.hookMethod(m, object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                // before 而不是 after：此时句柄一定已打开且在有效期内。
                                param.thisObject?.let { db ->
                                    try {
                                        observe(db)
                                    } catch (_: Throwable) {
                                        // 采集失败绝不影响微信自己的查询
                                    }
                                }
                            }
                        })
                        hooks++
                    } catch (t: Throwable) {
                        Log.w(TAG, "voice db: hook " + name + "." + m.name + " failed: " + t.message)
                    }
                }
            } catch (_: Throwable) {
                // 该类在这个微信版本不存在，换下一个
            }
        }
        Log.i(TAG, "voice db: handle hooks installed=" + hooks)
    }

    /**
     * 记下一个已打开的数据库句柄。
     *
     * 只接受主消息库：微信同时开着多个库（公共库、emoji 库…），
     * 若把公共库也收进来，查 `message` 表时它根本不存在该表 —— 白白多一倍的失败。
     */
    @Synchronized
    private fun observe(db: Any) {
        val isMain = known.getOrPut(db) {
            try {
                val path = db.javaClass.getMethod("getPath").invoke(db) as? String
                val name = path?.replace('\\', '/')?.substringAfterLast('/')
                name == MAIN_DB
            } catch (_: Throwable) {
                false
            }
        }
        if (!isMain) return
        handles.removeAll { it.get() == null || it.get() === db }
        handles.add(0, WeakReference(db))
        while (handles.size > MAX_HANDLES) handles.removeAt(handles.lastIndex)
    }

    /** 当前可用的主库句柄快照。可能为空 —— 用户还没打开过任何聊天时就是这样。 */
    private fun databases(): List<Any> = synchronized(this) { handles.mapNotNull { it.get() } }

    /**
     * 跑一条只读查询。**SQL 由本文件的调用方以字面量给出，绝不拼用户输入**，
     * 参数一律走 `?` 占位符，所以不存在注入面。
     */
    private fun query(db: Any, sql: String, args: Array<String>): Cursor {
        // 库可能已被微信关闭；对已关闭的库查询会抛异常，所以先复核。
        check(db.javaClass.getMethod("isOpen").invoke(db) == true) { "db closed" }
        // 找 rawQuery(String, String[])。参数退化为 Object[] 的版本也接受（数组是协变的）。
        val m: Method = db.javaClass.methods.firstOrNull { c ->
            c.name == "rawQuery" && c.parameterCount == 2 &&
                c.parameterTypes[0] == String::class.java &&
                c.parameterTypes[1].isArray &&
                c.parameterTypes[1].isAssignableFrom(Array<String>::class.java)
        } ?: error("rawQuery(String,String[]) not found")
        return m.invoke(db, sql, args) as? Cursor ?: error("query returned no cursor")
    }

    /**
     * 读取**微信自己已经转写好的**文字。
     *
     * 表：`VoiceTransText`，列：`msgId` / `cmsgId` / `content`。
     * 键：`msgId` = 消息 id（**PRIMARY KEY**），`cmsgId` = 语音文件标识（即消息的 `field_imgPath`）。
     *
     * 【为什么先查这张表】用户长按语音点过「转文字」、或对方发来时微信已自动转过，
     * 结果就落在这张表里。直接读出来**不需要触发任何转写、不发一个网络包、不耗一点算力**，
     * 比调用引擎快得多，而且不会打断用户正在进行的操作。
     *
     * ══════════════════════════════════════════════════════════════════════════════════
     * ★★ v10.1.0 修正：原来查的是 `WHERE msgId = ? AND cmsgId = ?`，这是**错的** ★★
     *
     * 【错在哪】`msgId` 是这张表的 **PRIMARY KEY**（建表语句见下），单查它就已经唯一确定一行。
     * 再 `AND cmsgId = ?` **不增加任何准确性，只增加假阴性**：
     * 新发出的语音 `imgPath` 可能还是空的、或在被微信回填的过程中，
     * 只要它与 `source.fileToken` 有一个字节不同，整条查询就查不到 ——
     * 而那一行**明明就在库里**。用户看到的就是"读不出转写、只显示 [语音]"。
     *
     * 【微信自己怎么做】对全 dex 的反汇编证明，微信查这张表**只用单一列**，从不组合：
     *     SELECT content FROM VoiceTransText WHERE msgId = ?      (fts.logic.f2.b)
     *     SELECT msgId FROM VoiceTransText                        (fts.logic.a2.i)
     *     SELECT * FROM VoiceTransText WHERE cmsgId=?             (storage.kb.Q0)
     * 也就是说微信把两个键**当成两条独立的路**，而不是一个复合条件。
     * 本函数现在的策略与它一致：**先用主键 msgId 查（最可靠），查不到再退回 cmsgId**。
     *
     * 建表语句（逐字节取自 classes5.dex，注意空格）：
     *     CREATE TABLE IF NOT EXISTS VoiceTransText (  msgId LONG PRIMARY KEY ,  cmsgId TEXT,  content TEXT default '' );
     * ══════════════════════════════════════════════════════════════════════════════════
     *
     * @return 转写文本；没有则 null（调用方据此决定是否触发引擎）
     */
    fun existingText(source: VoiceSource): String? {
        if (source.id <= 0L && source.fileToken.isBlank()) return null

        // 主键优先：单列查询，与微信自己的 fts 读法一致。
        val byId = if (source.id > 0L) {
            queryFirstColumn(
                "SELECT content FROM VoiceTransText WHERE msgId = ? LIMIT 1",
                arrayOf(source.id.toString()),
                "msgId=" + source.id,
            )
        } else {
            null
        }
        if (!byId.isNullOrBlank()) return byId

        // 退回 cmsgId：与微信 DAO 层 storage.kb.Q0 的查法一致。
        // 这一条覆盖"msgId 对不上但语音文件能对上"的情况（例如消息行被重建过）。
        if (source.fileToken.isNotBlank()) {
            val byFile = queryFirstColumn(
                "SELECT content FROM VoiceTransText WHERE cmsgId = ? LIMIT 1",
                arrayOf(source.fileToken),
                "cmsgId=" + source.fileToken,
            )
            if (!byFile.isNullOrBlank()) return byFile
        }
        return null
    }

    /**
     * 依次在每个已捕获的数据库句柄上执行一条**只读取单列单行**的查询。
     *
     * 抽出来是因为现有两个查询（msgId / cmsgId）走的是完全相同的"遍历句柄、单个失败就换下一个"逻辑；
     * 写两遍会让"某个句柄失败该不该继续"这种判断出现两个版本。
     */
    private fun queryFirstColumn(sql: String, args: Array<String>, what: String): String? {
        for (db in databases()) {
            try {
                val text = query(db, sql, args).use { c ->
                    if (c.moveToFirst()) c.getString(0)?.trim() else null
                }
                if (!text.isNullOrBlank()) {
                    Log.i(TAG, "voice db: reused an existing transcript (" + what + ")")
                    return text
                }
            } catch (t: Throwable) {
                // 单个句柄失败就换下一个；全都失败返回 null，调用方会走引擎转写。
                Log.w(TAG, "voice db: VoiceTransText read failed (" + what + "): " + t.message)
            }
        }
        return null
    }

    /**
     * 复核这条语音**此刻仍然存在且身份一致**。
     *
     * 【为什么每次都要复核】转写要轮询好几秒。这几秒里用户可能撤回、删除、或切了账号，
     * 此时若把先前拿到的文字写进本地记录，就会**给一条不存在的语音留下一行记录**。
     * 所以每一轮都拿 (talker, msgId) 回查 message 表，并逐字段比对。
     *
     * @return true 表示这条语音还在、且就是同一条
     */
    /**
     * 这一条语音**是否还在**（身份校验，不是内容校验）。
     *
     * ══════════════════════════════════════════════════════════════════════════════════
     * ★★ v10.0.0 修的用户报的 bug：「发了语音 转成功了 但却没有写入」★★
     *
     * 【原来的错误判据】本函数曾逐字段严格相等：
     *
     *     talker == source.talker && isSend == source.sent &&
     *     time == source.time && imgPath == source.fileToken && svrId == source.serverId
     *
     * 【为什么会错】真机日志：
     *     03:33:00.541 voice: transcribing id=16079311
     *     03:33:00.553 voice: id=16079311 initial state=NoTransform   <- 已经 submit 给微信
     *     03:33:01.155 voice: the message vanished or the account changed, id=16079311
     * 只过了 600ms 就判定"消息消失"。而实际发生的是：
     *   1. 我们 submit 后，微信开始**上传**这段语音；
     *   2. 上传过程中微信会**回填** `imgPath`（新语音的媒体文件名）与 `msgSvrId`（服务器分配的消息 id）
     *      —— 发出去之前这两个字段本来就是空的或占位的；
     *   3. 下一轮比对发现字段变了，于是把"**字段被正常回填**"误判成了"这条语音被删了"。
     *
     * 换句话说：**我把"这条语音正在被处理"的特征，当成了"这条语音已经不存在"的证据。**
     * 越是刚发出的新语音（最需要转写的那类），回填越必然发生，于是失败得越稳定。
     *
     * 【修法】只校验**身份**：这个 (talker, msgId) 上还挂着一条 type=34 的语音，就说明它还在。
     * 会变化的字段（imgPath / msgSvrId / createTime 的毫秒细节）**不参与判断** ——
     * 它们描述的是"这条语音被处理到哪一步了"，不是"它是否存在"。
     *
     * 【安全边界没有变松】"消息被删/撤回/换账号"这三种真正要防的情况，都会让这一行**查不到**
     * （删除会删行、换账号会换 talker），所以仍然会被正确拦下 —— 只是不再被字段回填误伤。
     * ══════════════════════════════════════════════════════════════════════════════════
     *
     * @return true 表示这条语音还在
     */
    fun stillMatches(source: VoiceSource): Boolean {
        if (source.id <= 0L || source.talker.isBlank()) return false
        for (db in databases()) {
            try {
                val ok = query(
                    db,
                    "SELECT talker FROM message " +
                        "WHERE talker = ? AND msgId = ? AND type = $TYPE_VOICE LIMIT 1",
                    arrayOf(source.talker, source.id.toString()),
                ).use { c ->
                    // 只要还有这一行就算在。talker 用查得到的值复核一次，
                    // 避免某个句柄返回了别的会话的同号消息（理论上不会，但代价极低）。
                    if (!c.moveToFirst()) return@use false
                    c.getString(0).orEmpty() == source.talker
                }
                if (ok) return true
            } catch (t: Throwable) {
                Log.w(TAG, "voice db: message verify failed: " + t.message)
            }
        }
        return false
    }

    /**
     * 从数据库把**宿主自己的消息对象**还原出来。
     *
     * 【为什么必须走宿主的方法，而不是自己 new 一个】
     * 转写引擎的 `submit(msg,...)` 需要的是**微信自己的 MsgInfo**，它内部还有一堆本模块
     * 不认识的字段（媒体状态、下载状态、扩展字段…）。只填我们认识的几个字段去调，
     * 引擎可能因为内部状态不完整而拒绝或行为异常。
     * 微信的消息类自带 `convertFrom(Cursor)`，让**它自己**从游标行还原，才是最忠实的做法。
     *
     * 【⚠️ 实测踩过的坑：所有读游标的动作都必须在 use{} 内部完成】
     *
     * 第一版是这样写的：
     *
     *     val row = query(db, sql).use { c -> if (c.moveToFirst()) c else null } ?: continue
     *     val talker = row.getString(...)          // <-- 这里必炸
     *     convert.invoke(instance, row)
     *
     * 真机日志给出：
     *     voice db: message restore failed: Attempting to access a closed CursorWindow.
     *     Most probable cause: cursor is deactivated prior to calling this method.
     *
     * 原因：`use{}` 在块退出时会**关闭游标**，而我把游标本身当返回值带了出来 ——
     * 拿到的是一个已经关闭的游标，之后每次 getString/invoke 都失败。
     * 这不是"偶发"或"时序问题"，是**必然**失败：use 的语义就是"退出即关"。
     *
     * 所以整个"读列 -> 校验 -> convertFrom"必须全部留在同一个 use 块内
     * （参考实现 voiceRecord 也正是这么写的：convertFrom 就在 use 里面）。
     *
     * @param messageClass `contract.messageClass`（= submit 的第 0 个参数类型）
     * @return 还原出的消息对象；任一步失败返回 null
     */
    fun messageFromDb(source: VoiceSource, messageClass: Class<*>?): Any? {
        if (messageClass == null) return null
        if (source.id <= 0L || source.talker.isBlank()) return null
        for (db in databases()) {
            try {
                // 用 runCatching 把"这个句柄失败"与"整体失败"分开：某个句柄读不到就换下一个，
                // 而不是直接放弃全部候选。
                val restored = runCatching {
                    query(
                        db,
                        "SELECT * FROM message WHERE talker = ? AND msgId = ? AND type = $TYPE_VOICE LIMIT 1",
                        arrayOf(source.talker, source.id.toString()),
                    ).use { row ->
                        if (!row.moveToFirst()) return@use null

                        // 身份复核：确认这一行确实是我们要的那条语音，再拿去还原。
                        val talker = row.getString(row.getColumnIndexOrThrow("talker")).orEmpty()
                        val isSend = row.getLong(row.getColumnIndexOrThrow("isSend")).toInt()
                        if (talker != source.talker || isSend != source.sent) return@use null

                        val ctor = messageClass.getDeclaredConstructor().apply { isAccessible = true }
                        val instance = ctor.newInstance()
                        val convert = messageClass.getMethod("convertFrom", Cursor::class.java)
                            .apply { isAccessible = true }
                        convert.invoke(instance, row)
                        instance
                    }
                }.getOrNull()
                if (restored != null) return restored
            } catch (t: Throwable) {
                Log.w(TAG, "voice db: message restore failed: " + t.message)
            }
        }
        return null
    }

    /** 诊断用：当前拿到几个主库句柄。 */
    fun handleCount(): Int = databases().size
}
