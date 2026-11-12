package com.luoliaoo.wechat.data

/**
 * One follow-up exchange inside a card.
 *
 * @param fromMe  true for the user's question (right side), false for the AI's answer (left)
 * @param pending true for a question whose answer is still being generated; the card shows
 *                "AI 正在思考 ing" in place of the answer
 */
data class ChatTurn(
    val fromMe: Boolean,
    val text: String,
    val time: Long = System.currentTimeMillis(),
    val pending: Boolean = false,

    /**
     * ★ v10.3.0：流式输出中**已经收到的那半截答案**。
     *
     * ─────────────────────────────────────────────────────────────
     * 【为什么放在数据模型里，而不是直接往 TextView 里写】
     * 本模块的卡片是 overlay，画在悬浮面板里，而 `FloatingPanel.renderCard()` **每次 render
     * 都 new 一张全新的 `AnalysisCard`**。所以"找到屏幕上那个活着的控件直接改写"这条路
     * 在结构上就是走不通的：抓到的引用下一次重画就作废，写进去的字也会被覆盖。
     *
     * 把半截答案放进数据模型后，流式就变成一件很普通的事：
     *   写 `partial` → `FloatingPanel.refresh()` → 既有渲染路径画出来。
     * 全程不持有任何 View 引用，复用唯一一条已被验证可用的渲染路径。
     *
     * 【与 text 的分工】
     *   * `pending == true` 且 `partial` 为空 → 画「AI 正在思考 ing」（首字还没到）；
     *   * `pending == true` 且 `partial` 非空 → 画 `partial`（正在逐字出）；
     *   * `pending == false`              → 画 `text`（已落定，这是权威答案）。
     * 落定时 `pending` 置 false、`text` 写真答案，`partial` 自然被绕过，
     * 所以不需要额外清理它就永远不会"抢戏"。
     *
     * 【为什么要持久化】它跟着 CardStore 一起存盘，所以微信被杀掉后重开，
     * 用户看到的还是"刚才那句已经出了大半"的样子，而不是退回一个空的思考中。
     */
    val partial: String = "",
)
