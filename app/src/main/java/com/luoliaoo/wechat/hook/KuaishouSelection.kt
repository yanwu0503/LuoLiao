package com.luoliaoo.wechat.hook

/**
 * Pure, unit-testable rules for reading Kuaishou's multi-select state.
 *
 * ============================== WHY THIS IS SEPARATE, AND WHICH BUG IT PREVENTS ===============
 *
 * The blueprint ORIGINALLY specified reading the selection through `frj.p.b():List` (reached from
 * `sqj.c.J:frj.i`), and it flagged that route's weakness: the list is BARE (no generic information) and
 * the message-side model `MsgMulltiChooseResponse$ChoosedMsg` carries only
 * `mFromUserId:String` + `mSeq:J`. A list of those cannot produce message objects, so the batch
 * analysis would have had nothing to describe.
 *
 * Reading `esj.l.g(KwaiMsg)` -- the method that POPULATES the selection -- settled it by disassembly:
 *
 *     [0020] iget-object v0, esj.l->i:Ljava/util/Map;
 *     [0024] invoke-static o2k.b2->b(KwaiMsg)Ljava/lang/String;   <-- getSender() + '_' + getClientSeq()
 *     [002c] invoke-interface java.util.Map->put(key, msg)
 *
 * So the map's VALUES are the real `KwaiMsg` objects, and the KEY is a composite string. This class
 * encodes the consequence: **read the values, never reconstruct the key.**
 *
 * Reconstructing the key was the tempting mistake. It would work for the common case and fail silently
 * otherwise -- e.g. if `getClientSeq()` is 0 for a message that has not been sent yet, or if the sender
 * spelling differs between the local echo and the delivered copy. The failure mode is an EMPTY selection
 * for a selection the user can see on screen, which reads as "the feature is broken" with no clue why.
 * Reading values cannot fail that way, because it never has to agree with the host about a key format.
 *
 * Values are also deduplicated by identity: the same message selected twice (a double tap on the
 * checkbox) must not be analysed twice, and an identical TALKER+text pair is NOT a duplicate -- two
 * identical messages really are two messages, and dropping one would silently lose a line of chat.
 *
 * Comments ASCII-only; Chinese uses \uXXXX (see core/Log.kt).
 */
object KuaishouSelection {

    /**
     * The selected messages, in Map iteration order.
     *
     * Null values are skipped rather than converted to a placeholder: a null entry is not a message, and
     * passing a non-message into `describeMessages` would push it through the whole pipeline.
     *
     * Deduplicated by IDENTITY, so two genuinely identical messages both survive while the very same
     * object added twice collapses to one.
     */
    fun valuesOf(map: Map<*, *>?): List<Any> {
        if (map == null || map.isEmpty()) return emptyList()
        val out = ArrayList<Any>(map.size)
        val seen = java.util.Collections.newSetFromMap(
            java.util.IdentityHashMap<Any, Boolean>(),
        )
        for (v in map.values) {
            if (v == null) continue
            if (!seen.add(v)) continue
            out.add(v)
        }
        return out
    }

    /**
     * True when the selection should take the BATCH path.
     *
     * `> 1` rather than `>= 1`, and that matters: Kuaishou routes its own 多选 item to
     * `esj.z.g(msg)`, so entering multi-select pre-selects the long-pressed message. Taking the batch
     * path for a single selection would show the same message through a different dialog and make a
     * plain "analyse this message" press feel like a mode change. The single-message path is the right
     * answer there.
     */
    fun isBatch(list: List<Any>?): Boolean = (list?.size ?: 0) > 1

    /**
     * A short description of the selection for the log, so a batch run states what it saw.
     *
     * Class names are reduced to simple names and capped, because a 200-message selection would
     * otherwise write a multi-kilobyte line into a log that is read by a human on a phone.
     */
    fun describeForLog(list: List<Any>?, limit: Int = 6): String {
        if (list.isNullOrEmpty()) return "<empty>"
        val names = list.take(limit).joinToString(",") { it.javaClass.simpleName }
        return if (list.size > limit) names + ",...(" + list.size + " total)" else names
    }

    /**
     * The KEY format the host uses, reproduced ONLY for diagnostics.
     *
     * Deliberately not used to look anything up: see the header. It exists so a log line can show what
     * the host recorded alongside what we read, which makes a future mismatch diagnosable instead of
     * mysterious.
     */
    fun keyOf(sender: String?, clientSeq: Long): String =
        (sender ?: "").trim() + '_' + clientSeq
}
