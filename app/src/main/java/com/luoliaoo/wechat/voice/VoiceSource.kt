package com.luoliaoo.wechat.voice

/**
 * v10.0.0: 一条语音消息的**身份**，仅此而已。
 *
 * 【为什么要有这个类，而不是直接用 host 的 MsgInfo】
 * 转写要走「后台线程查库 -> 主线程调宿主 -> 再回后台查库」的多段流程，中间还要跨线程传递。
 * 如果直接持有宿主的 MsgInfo 对象，就会把宿主的类引用泄漏到模块的队列里；
 * 而宿主随时可能回收/重建行对象（RecyclerView 复用），持有一个"会变的"对象是错的。
 * 所以这里只留**定位这条语音所必需的最小字段集**，全部是不可变值。
 *
 * 【字段与微信数据库列的对应】(见 messages 表 / VoiceTransText 表)
 *   talker    -> field_talker  / message.talker    会话 id（wxid 或 xxx@chatroom）
 *   id        -> field_msgId   / message.msgId     本机消息 id，也是 state()/text() 的键
 *   time      -> field_createTime / message.createTime
 *   sent      -> field_isSend  / message.isSend    0=对方 1=自己
 *   fileToken -> field_imgPath / message.imgPath   **语音文件名**；微信对语音复用了 imgPath 字段，
 *                                                  它同时是 VoiceTransText.cmsgId 和 text() 的第 2 个参数
 *   serverId  -> field_msgSvrId / message.msgSvrId
 *
 * 【为什么带 time 和 serverId —— 它们不参与转写】
 * 它们只用于**身份复核**：从数据库按 (talker, msgId) 取回一行后，把所有字段拼回来与本文对象比一次，
 * 全等才认。少了这一步，换账号 / 语音被删 / 行被复用之后，可能把**别人的**转写结果写进当前会话。
 */
data class VoiceSource(
    val talker: String,
    val id: Long,
    val time: Long,
    val sent: Int,
    val fileToken: String,
    val serverId: Long,
) {
    /**
     * 失败冷却表的键。
     *
     * 用「长度前缀 + 值」拼接而不是直接字符串相连：直接相连会出现 ("ab","c") 与 ("a","bc") 撞成同一个键，
     * 于是对一条语音的失败冷却会误伤另一条。长度前缀让每个字段的边界无歧义。
     */
    val key: String
        get() = listOf(talker, id.toString(), time.toString(), sent.toString(), fileToken, serverId.toString())
            .joinToString("") { "${it.length}:$it" }

    /** 日志里**只打 id**：正文和媒体标识不进日志。 */
    override fun toString(): String = "VoiceSource(id=$id)"
}

/** 本地历史里用来标记"这条是语音转出来的文字"的前缀。 */
object VoicePrefix {
    const val TEXT = "[\u8bed\u97f3\u8f6c\u6587\u5b57]"      // [语音转文字]

    /** 把转写结果包装成本地记录用的文本。 */
    fun wrap(transcript: String): String = TEXT + transcript
}
