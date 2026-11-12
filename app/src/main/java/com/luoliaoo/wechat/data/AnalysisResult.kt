package com.luoliaoo.wechat.data

/**
 * AI analysis result (fixed structure).
 *
 * `Option.reply` carries the ready-to-send sentence for that intent (item 4): the card shows
 * a 复制 button next to each real-intent row so the user can copy it without typing.
 */
data class AnalysisResult(
    /** Literal question, e.g. "is she really asking whether you remember?" */
    val question: String,
    /** Probabilities for the literal question, e.g. [是 7%, 不是 93%] */
    val intentOptions: List<Option>,
    /** Real underlying intents */
    val realIntent: List<Option>,
    /** Anger level 0..10 */
    val angerLevel: Int,
    /** Optional one-line summary */
    val comment: String?,
    /** Raw model output when JSON parsing failed */
    val rawFallback: String? = null,
    /**
     * item 4: token usage reported by the API, so the card can show what the analysis cost.
     *
     * A separate data class rather than three Ints, so a missing usage block is representable (some
     * endpoints omit it).
     */
    val usage: Usage? = null,
) {
    /** Token counts for one request. */
    data class Usage(
        val prompt: Int,
        val completion: Int,
    ) {
        val total: Int get() = prompt + completion
    }

    /**
     * One option.
     *
     * @param reply a suggested reply the user can copy (real_intent only; may be null)
     */
    data class Option(
        val label: String,
        val probability: Int,
        /** A suggested reply, used by the real-intent rows. */
        val reply: String? = null,
        /** Why this option was given, shown under the literal-question options as 原因：XX. */
        val reason: String? = null,
    )

    val isFallback: Boolean get() = rawFallback != null

    companion object {
        fun fallback(text: String) = AnalysisResult(
            question = "",
            intentOptions = emptyList(),
            realIntent = emptyList(),
            angerLevel = -1,
            comment = null,
            rawFallback = text,
        )
    }
}
