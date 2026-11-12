package com.luoliaoo.wechat.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.luoliaoo.wechat.core.Log
import java.io.File

/**
 * Reads chat history straight out of WeChat's own message database.
 *
 * !! UNUSED: WeChat encrypts EnMicroMsg.db with SQLCipher. A plain SQLiteDatabase.openDatabase gets
 *    SQLiteDatabaseCorruptException "file is not a database" (code 26 SQLITE_NOTADB), which is what
 *    the log showed, so this class cannot work without a SQLCipher implementation.
 *
 *    Kept for reference; history is gathered by AnalysisController.deepCollect instead.
 *
 * Reads chat history straight out of WeChat's own message database (item 3).
 *
 * WHY: collecting from the RecyclerView only ever returns the rows currently attached, which is why
 * the analysed window kept shifting ("获取最早的比选中信息早十几条"). The database holds the whole
 * conversation, so the scope no longer depends on how far the user scrolled.
 *
 * SAFETY: the database is opened READ-ONLY, in a try/catch that falls back to adapter collection.
 * WeChat may keep the file locked or use WAL; a failure is logged and never fatal.
 */
object DbReader {

    private const val TAG = "LuoLiao"

    /** Newest-first page size when paging the conversation. */
    private const val PAGE = 500

    /** Upper bound so a huge conversation cannot exhaust memory. */
    private const val MAX_FETCH = 4000

    @Volatile
    private var dbFile: File? = null

    @Volatile
    private var tried = false

    /**
     * Loads up to [limit] of the most recent text messages for `talker`.
     *
     * @return the rows oldest-first, or an empty list when the database is unavailable.
     */
    fun recentMessages(ctx: Context, talker: String, limit: Int = MAX_FETCH): List<HistoryStore.Line> {
        if (talker.isBlank()) return emptyList()
        val f = locate(ctx) ?: return emptyList()
        var db: SQLiteDatabase? = null
        return try {
            db = SQLiteDatabase.openDatabase(
                f.absolutePath, null, SQLiteDatabase.OPEN_READONLY,
            )
            val out = ArrayList<HistoryStore.Line>(512)
            var offset = 0
            while (out.size < limit) {
                val page = queryPage(db, talker, PAGE, offset)
                if (page.isEmpty()) break
                out.addAll(page)
                if (page.size < PAGE) break
                offset += PAGE
            }
            val ordered = out.sortedBy { it.time }
            Log.i(TAG, "db: loaded " + ordered.size + " message(s) for " + talker)
            ordered
        } catch (t: Throwable) {
            Log.w(TAG, "db read failed for " + talker + ": " + t.javaClass.simpleName +
                ": " + t.message)
            emptyList()
        } finally {
            try {
                db?.close()
            } catch (_: Throwable) {
                // already closed
            }
        }
    }

    private fun queryPage(
        db: SQLiteDatabase,
        talker: String,
        page: Int,
        offset: Int,
    ): List<HistoryStore.Line> {
        val out = ArrayList<HistoryStore.Line>(page)
        // type = 1 is a plain text message. Columns are guarded: a missing column throws and the
        // caller falls back, rather than silently producing wrong data.
        val sql = "SELECT msgId, createTime, isSend, content FROM message " +
            "WHERE talker = ? AND type = 1 AND content IS NOT NULL AND content != '' " +
            "ORDER BY createTime DESC LIMIT ? OFFSET ?"
        db.rawQuery(sql, arrayOf(talker, page.toString(), offset.toString())).use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val time = c.getLong(1)
                val isSend = c.getInt(2) != 0
                val content = c.getString(3) ?: continue
                if (content.isBlank()) continue
                out.add(
                    HistoryStore.Line(
                        msgId = id,
                        time = time,
                        isSend = isSend,
                        text = content,
                        speaker = talker,
                    ),
                )
            }
        }
        return out
    }

    /** Finds EnMicroMsg.db, preferring the newest account directory. */
    private fun locate(ctx: Context): File? {
        dbFile?.let { return it }
        if (tried) return null
        tried = true
        return try {
            val microMsg = File(ctx.filesDir.parentFile, "MicroMsg")
            if (!microMsg.isDirectory) {
                Log.w(TAG, "db: MicroMsg dir missing at " + microMsg.absolutePath)
                return null
            }
            val candidates = microMsg.listFiles { f ->
                f.isDirectory && File(f, "EnMicroMsg.db").isFile
            }?.sortedByDescending { File(it, "EnMicroMsg.db").lastModified() }
            val dir = candidates?.firstOrNull()
            if (dir == null) {
                Log.w(TAG, "db: no EnMicroMsg.db under " + microMsg.absolutePath)
                return null
            }
            val f = File(dir, "EnMicroMsg.db")
            Log.i(TAG, "db located: " + f.absolutePath + " size=" + f.length())
            dbFile = f
            f
        } catch (t: Throwable) {
            Log.w(TAG, "db locate failed: " + t.message)
            null
        }
    }

    /** Forces a re-locate, e.g. after switching accounts. */
    fun reset() {
        dbFile = null
        tried = false
    }
}
