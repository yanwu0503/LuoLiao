package com.luoliaoo.wechat.hook

/**
 * Decides whether a tapped long-press option is OURS, i.e. whether the host's own dispatch must be
 * suppressed for it.
 *
 * ============================== WHY THIS IS A SEPARATE, TESTABLE DECISION ==============================
 * `KuaishouHooks.installMenuClickHook` calls `chain.skipOriginal()` when this returns true, which stops
 * the host running the tapped row's feature. Two failure modes are possible and BOTH are severe:
 *
 *   * FALSE NEGATIVE (miss one of our rows) -> tapping 分析 opens 「个性化装扮」 or starts 「多选」,
 *     the exact device bug we are fixing.
 *   * FALSE POSITIVE (match a HOST row)     -> the host's own 「撤回」/「删除」/「多选」 silently stops
 *     working, which is worse: we would be breaking the app the user already had.
 *
 * The decision is therefore kept as a pure function over an identity set so both directions can be
 * asserted, instead of being an inline expression that only the device can falsify.
 *
 * Note the deliberate asymmetry: a null option is NOT ours, because the host passes a real `r9j.k` and a
 * null would come from a re-entrant or reordered call we do not understand. Treating "unknown" as "ours"
 * is what would start breaking host rows.
 */
internal object KuaishouMenuRouting {

    /**
     * True when [tapped] is one of the option instances THIS MODULE created.
     *
     * @param tapped the option handed to the host's click callback (may be null)
     * @param ourInstanceKeys identity hashes of rows created by this module
     */
    fun isOurRow(tapped: Any?, ourInstanceKeys: Set<Int>): Boolean {
        if (tapped == null) return false
        return ourInstanceKeys.contains(System.identityHashCode(tapped))
    }
}
