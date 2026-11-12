package com.luoliaoo.wechat

import com.luoliaoo.wechat.ai.ChatLine
import com.luoliaoo.wechat.ai.PromptBuilder
import com.luoliaoo.wechat.ai.ScopeMode
import com.luoliaoo.wechat.core.HostApp
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v5.0.0: 文案宿主自适应。
 *
 * WHY THIS TEST EXISTS: every user-facing string and every model prompt is built for a HOST. Before
 * 5.0.0 they were hardcoded to 微信 / 微信号, so a Douyin user read 「我的微信号是…」 and the model was
 * told it was reading 微信聊天记录. The bug was invisible in the UI, which is exactly why it is pinned
 * here rather than left to a device check.
 *
 * Comments are ASCII-only on purpose (see core/Log.kt).
 */
class HostTextTest {

    private val base = 1_700_000_000_000L

    private fun lines() = listOf(
        ChatLine(base, false, "在吗", speaker = "peer"),
        ChatLine(base + 1000, true, "在", speaker = "me"),
    )

    /** Leaves the process in the historical WeChat state, so no other test is affected. */
    @After
    fun tearDown() {
        HostApp.setUiOverrideForTest(null)
    }

    // ------------------------------------------------------------ HostApp.say itself

    @Test
    fun sayRewritesBothLabelsForDouyin() {
        val s = HostApp.DOUYIN.say("我的微信号是：abc，在微信里长按")
        assertTrue("微信号 must become 抖音号: $s", s.contains("我的抖音号是：abc"))
        assertTrue("微信 must become 抖音: $s", s.contains("在抖音里长按"))
        assertFalse("no 微信 may survive: $s", s.contains("微信"))
    }

    @Test
    fun sayIsIdentityForWeChat() {
        val src = "我的微信号是：abc，在微信里长按"
        // Byte-for-byte identity, so a WeChat build cannot regress by going through this layer.
        assertEquals(src, HostApp.WECHAT.say(src))
    }

    @Test
    fun theLongerLabelIsReplacedBeforeTheShorterOne() {
        // If 微信 were replaced first, 微信号 would come out as 抖音号 only by luck; the real risk is
        // 「微信号」 -> 「抖音号号」 or a half-eaten word. Pin the exact output.
        assertEquals("抖音号", HostApp.DOUYIN.say("微信号"))
        assertEquals("抖音", HostApp.DOUYIN.say("微信"))
    }

    @Test
    fun unknownHostGetsNeutralText() {
        assertEquals("ID", HostApp.UNKNOWN.say("微信号"))
        assertEquals("App", HostApp.UNKNOWN.say("微信"))
    }

    @Test
    fun labelFallsBackToNotInstalled() {
        assertEquals("微信 8.0.77", HostApp.label(HostApp.WECHAT, "8.0.77"))
        assertEquals("抖音 39.9.0", HostApp.label(HostApp.DOUYIN, "39.9.0"))
        assertEquals("抖音 " + HostApp.NOT_INSTALLED, HostApp.label(HostApp.DOUYIN, null))
        assertEquals("抖音 " + HostApp.NOT_INSTALLED, HostApp.label(HostApp.DOUYIN, "  "))
    }

    @Test
    fun realHostsListEveryHostAndExcludeUnknown() {
        // v6.0.0: this used to assert `listOf(WECHAT, DOUYIN)` -- an EXACT two-element list. Adding
        // Soul made that assertion wrong the moment the enum grew, which is the useful kind of wrong:
        // the test failed loudly instead of silently passing while Soul was missing from every
        // host-enumerating screen. It is written as an exact list on purpose (not a `contains`), so
        // the NEXT host also has to be added here deliberately rather than slipping in.
        //
        // v7.0.0: that "next host" arrived (KUAISHOU) and this exact-list assertion is what made it
        // impossible to add the constant without also visiting this file. Kept as an exact list.
        //
        // v8.0.0: QQ arrived and this assertion caught it again -- exactly as designed. The list is
        // still EXACT (not a `contains`), so a sixth host must also be added here deliberately.
        //
        // v9.5.0: the sixth host arrived (XIAOHONGSHU) and this assertion caught it a third time, which
        // is the whole point of writing it as an exact list rather than a `contains`.
        assertEquals(
            listOf(
                HostApp.WECHAT,
                HostApp.DOUYIN,
                HostApp.SOUL,
                HostApp.KUAISHOU,
                HostApp.QQ,
                HostApp.XIAOHONGSHU,
            ),
            HostApp.REAL_HOSTS,
        )
        assertFalse(HostApp.REAL_HOSTS.contains(HostApp.UNKNOWN))
    }

    @Test
    fun xiaohongshuPackageResolvesToXiaohongshu() {
        // The single mapping from a package to a host; every hook is gated on its answer.
        assertEquals(HostApp.XIAOHONGSHU, HostApp.of("com.xingin.xhs"))
        // A cloner/fork package must follow its parent, the same way Douyin's and Soul's prefixes do.
        assertEquals(HostApp.XIAOHONGSHU, HostApp.of("com.xingin.xhs.clone"))
    }

    /**
     * Xiaohongshu's Java namespace must NOT be mistaken for its Android package.
     *
     * WHY THIS IS WORTH PINNING: the APK's INTERNAL classes live under `com.xingin.` but the app's own
     * package is `com.xingin.xhs`. A package test written as "starts with com.xingin." would therefore
     * claim `com.xingin.comment`, `com.xingin.ai_engine_plugin` -- and every other app under that
     * namespace -- as this host, installing the wrong hooks into a stranger's process. That is exactly
     * the failure `HostApp.of` exists to prevent, and it is pure string logic, so it is pinned here
     * without a device.
     */
    @Test
    fun xiaohongshuNamespaceIsNotItsAndroidPackage() {
        assertNotEquals(HostApp.XIAOHONGSHU, HostApp.of("com.xingin.comment"))
        assertNotEquals(HostApp.XIAOHONGSHU, HostApp.of("com.xingin"))
        assertNotEquals(HostApp.XIAOHONGSHU, HostApp.of("com.xingin.ai_engine_plugin"))
    }

    @Test
    fun scopePackagesCoverEveryHostWithoutDuplicates() {
        val pkgs = HostApp.scopePackages()
        assertTrue(pkgs.contains("com.tencent.mm"))
        assertTrue(pkgs.contains("com.ss.android.ugc.aweme"))
        // v6.0.0
        assertTrue(pkgs.contains("cn.soulapp.android"))
        // v7.0.0
        assertTrue(pkgs.contains("com.smile.gifmaker"))
        // v8.0.0
        assertTrue(pkgs.contains("com.tencent.mobileqq"))
        // v9.5.0
        assertTrue(pkgs.contains("com.xingin.xhs"))
        assertEquals("no package may appear twice", pkgs.size, pkgs.toSet().size)
    }

    // ------------------------------------------------------------ v6.0.0: Soul host resolution

    /**
     * Soul's package resolves to SOUL, its prefix rule covers a cloner, and nothing else moved.
     *
     * WHY THIS IS A UNIT TEST: `HostApp.of` is THE single mapping from a package to a host, and every
     * hook in the module is gated on its answer. A wrong answer here is not a cosmetic bug -- it either
     * installs WeChat's class-name probes into a stranger's process or refuses to hook Soul at all.
     * It is pure string logic, so it can be pinned without a device (there is none attached).
     */
    @Test
    fun soulPackageResolvesToSoul() {
        assertEquals(HostApp.SOUL, HostApp.of("cn.soulapp.android"))
        // A third-party cloner keeps the package and appends a suffix; the prefix rule covers it.
        assertEquals(HostApp.SOUL, HostApp.of("cn.soulapp.android.clone"))
        // The two existing hosts must be unaffected -- this is the regression half of the test.
        assertEquals(HostApp.WECHAT, HostApp.of("com.tencent.mm"))
        assertEquals(HostApp.WECHAT, HostApp.of("com.tencent.mm.u1"))
        assertEquals(HostApp.DOUYIN, HostApp.of("com.ss.android.ugc.aweme"))
        assertEquals(HostApp.UNKNOWN, HostApp.of("com.example.unrelated"))
        assertEquals(HostApp.UNKNOWN, HostApp.of(null))
        assertEquals(HostApp.UNKNOWN, HostApp.of(""))
        // A near-miss package must NOT be captured by the Soul prefix.
        assertEquals(HostApp.UNKNOWN, HostApp.of("cn.soulapp.androids"))
        assertEquals(HostApp.UNKNOWN, HostApp.of("cn.soulapp"))
    }

    @Test
    fun soulSayRewritesTheWeChatWording() {
        val s = HostApp.SOUL.say("我的微信号是：abc，在微信里长按")
        assertTrue("微信号 must become Soul号: $s", s.contains("我的Soul号是：abc"))
        assertTrue("微信 must become Soul: $s", s.contains("在Soul里长按"))
        assertFalse("no 微信 may survive: $s", s.contains("微信"))
    }

    @Test
    fun soulLabelRendersItsVersionBadge() {
        assertEquals("Soul 6.37.0", HostApp.label(HostApp.SOUL, "6.37.0"))
        assertEquals("Soul " + HostApp.NOT_INSTALLED, HostApp.label(HostApp.SOUL, null))
    }

    @Test
    fun soulIdentityIsTheSoulPackage() {
        assertEquals(listOf("cn.soulapp.android"), HostApp.SOUL_PACKAGES)
        assertEquals("cn.soulapp.android", HostApp.SOUL.packages.firstOrNull())
        assertTrue(HostApp.SOUL.isSupported)
        assertEquals("Soul", HostApp.SOUL.displayName)
    }

    // ------------------------------------------------------------ v7.0.0: Kuaishou host resolution

    /**
     * Kuaishou's package resolves to KUAISHOU, its prefix rule covers a cloner, and nothing else moved.
     *
     * WHY THIS IS A UNIT TEST, restated for the fourth host because the stakes grow with each one: the
     * prefix rules are now four deep, and `com.ss.android.ugc.aweme.` (Douyin) and
     * `com.smile.gifmaker.` (Kuaishou) both begin with `com.s`, which is the first time two hosts could
     * have been confused by a sloppy prefix. This pins that they are not.
     */
    @Test
    fun kuaishouPackageResolvesToKuaishou() {
        assertEquals(HostApp.KUAISHOU, HostApp.of("com.smile.gifmaker"))
        // A third-party cloner keeps the package and appends a suffix; the prefix rule covers it.
        assertEquals(HostApp.KUAISHOU, HostApp.of("com.smile.gifmaker.clone"))
        // The three existing hosts must be unaffected -- this is the regression half of the test.
        assertEquals(HostApp.WECHAT, HostApp.of("com.tencent.mm"))
        assertEquals(HostApp.WECHAT, HostApp.of("com.tencent.mm.u1"))
        assertEquals(HostApp.DOUYIN, HostApp.of("com.ss.android.ugc.aweme"))
        assertEquals(HostApp.SOUL, HostApp.of("cn.soulapp.android"))
        assertEquals(HostApp.UNKNOWN, HostApp.of("com.example.unrelated"))
        assertEquals(HostApp.UNKNOWN, HostApp.of(null))
        assertEquals(HostApp.UNKNOWN, HostApp.of(""))
        // A near-miss package must NOT be captured by the Kuaishou prefix.
        assertEquals(HostApp.UNKNOWN, HostApp.of("com.smile.gifmakers"))
        assertEquals(HostApp.UNKNOWN, HostApp.of("com.smile"))
        // THE NEAR-MISS THAT MATTERS: the Douyin prefix and the Kuaishou prefix share `com.s`, so a
        // package under one must never come back as the other. `com.ss.` must stay Douyin and
        // `com.smile.` must stay Kuaishou -- neither prefix is a leading substring of the other.
        assertEquals(HostApp.DOUYIN, HostApp.of("com.ss.android.ugc.aweme.lite"))
        assertEquals(HostApp.DOUYIN, HostApp.of("com.ss.android.ugc.aweme.multi"))
        // A suffix after the Kuaishou package IS Kuaishou (that is the clone rule, not a leak), and it
        // must not be mistaken for Douyin even though its name mentions it.
        assertEquals(HostApp.KUAISHOU, HostApp.of("com.smile.gifmaker.notdouyin"))
    }

    @Test
    fun kuaishouSayRewritesTheWeChatWording() {
        val s = HostApp.KUAISHOU.say("我的微信号是：abc，在微信里长按")
        assertTrue("微信号 must become 快手号: $s", s.contains("我的快手号是：abc"))
        assertTrue("微信 must become 快手: $s", s.contains("在快手里长按"))
        assertFalse("no 微信 may survive: $s", s.contains("微信"))
    }

    @Test
    fun kuaishouLabelRendersItsVersionBadge() {
        assertEquals("快手 14.8.30.50423", HostApp.label(HostApp.KUAISHOU, "14.8.30.50423"))
        assertEquals("快手 " + HostApp.NOT_INSTALLED, HostApp.label(HostApp.KUAISHOU, null))
    }

    @Test
    fun kuaishouIdentityIsTheKuaishouPackage() {
        assertEquals(listOf("com.smile.gifmaker"), HostApp.KUAISHOU_PACKAGES)
        assertEquals("com.smile.gifmaker", HostApp.KUAISHOU.packages.firstOrNull())
        assertTrue(HostApp.KUAISHOU.isSupported)
        assertEquals("快手", HostApp.KUAISHOU.displayName)
        assertEquals("快手号", HostApp.KUAISHOU.idLabel)
    }

    /** v7.0.0: the model prompt must name 快手 / 快手号, and no 微信 may reach it. */
    @Test
    fun promptNamesKuaishouWhenRunningInsideKuaishou() {
        HostApp.setUiOverrideForTest(null)
        HostApp.setCurrent(HostApp.KUAISHOU)
        try {
            val p = PromptBuilder.build(lines(), lines()[0], ScopeMode.COUNT, myWxid = "ks_me")
            assertTrue("must call the chat a 快手 chat: $p", p.contains("以下是一段快手聊天记录"))
            assertTrue("must label my id 快手号: $p", p.contains("我的快手号是：ks_me"))
            assertFalse("no 微信 may reach the model: $p", p.contains("微信"))
        } finally {
            HostApp.setCurrent(HostApp.WECHAT)
        }
    }

    /** v7.0.0: `ofProcess` must answer KUAISHOU for both the package and the clone-suffix rule. */
    @Test
    fun ofProcessResolvesKuaishou() {
        assertEquals(HostApp.KUAISHOU, HostApp.ofProcess("com.smile.gifmaker", "com.smile.gifmaker"))
        // A Kuaishou auxiliary process carries a `:` suffix; the package already decides it.
        assertEquals(HostApp.KUAISHOU, HostApp.ofProcess("com.smile.gifmaker", "com.smile.gifmaker:push"))
        // A clone can report a rewritten PACKAGE while the PROCESS keeps the real name.
        assertEquals(HostApp.KUAISHOU, HostApp.ofProcess("com.unknown.clone.pkg", "com.smile.gifmaker:im"))
        // A recognised package WINS over the process name.
        assertEquals(HostApp.KUAISHOU, HostApp.ofProcess("com.smile.gifmaker", "com.tencent.mm"))
        // And a Kuaishou process name must NOT be answered as Douyin.
        assertEquals(HostApp.DOUYIN, HostApp.ofProcess("com.unknown.clone.pkg", "com.ss.android.ugc.aweme:push"))
    }

    // ------------------------------------------------------------ v8.0.0: QQ host resolution

    /**
     * v8.0.0: QQ's package resolves to QQ, its prefix rule covers a cloner, and WeChat is untouched.
     *
     * ===================== WHY THIS TEST IS THE HIGHEST-VALUE ONE FOR QQ =====================
     * QQ is the FIRST host that shares a VENDOR prefix with an existing host: both QQ and WeChat ship
     * `com.tencent.*` packages. Every previous host pair diverged in the first two segments
     * (`com.ss.` vs `com.smile.`), so the "no prefix is a prefix of another" argument was easy to
     * verify by eye. Here it is not: `com.tencent.mm.` and `com.tencent.mobileqq.` both start
     * `com.tencent.m`, and a careless edit -- collapsing either rule to a bare `com.tencent.` test --
     * would silently route EVERY QQ message through the WeChat reader, or hook QQ's classes inside
     * WeChat. Both failures are invisible at install time and show up only as "分析 does nothing".
     *
     * The assertions below therefore pin BOTH directions, and the negative case
     * (`com.tencent.mobileqqi`) is included deliberately: it is a DIFFERENT app (QQ International) that
     * this host does NOT claim to support, and it is the exact string a too-greedy prefix rule would
     * wrongly capture.
     */
    @Test
    fun qqPackageResolvesToQqAndDoesNotStealWeChat() {
        // --- QQ itself, both spellings of the rule ---
        assertEquals(HostApp.QQ, HostApp.of("com.tencent.mobileqq"))
        // A third-party cloner keeps the package and appends a suffix; the prefix rule covers it.
        assertEquals(HostApp.QQ, HostApp.of("com.tencent.mobileqq.clone"))

        // --- WeChat must be COMPLETELY unaffected (the regression half) ---
        assertEquals(HostApp.WECHAT, HostApp.of("com.tencent.mm"))
        assertEquals(HostApp.WECHAT, HostApp.of("com.tencent.mm.u1"))
        assertEquals(HostApp.WECHAT, HostApp.of("com.tencent.mm.clone"))

        // --- THE COLLISION CASES: neither host may answer for the other ---
        // A WeChat prefix test must not capture QQ, and a QQ prefix test must not capture WeChat.
        assertNotEquals(HostApp.WECHAT, HostApp.of("com.tencent.mobileqq"))
        assertNotEquals(HostApp.QQ, HostApp.of("com.tencent.mm"))
        // A bare-vendor string is not any host.
        assertEquals(HostApp.UNKNOWN, HostApp.of("com.tencent"))
        assertEquals(HostApp.UNKNOWN, HostApp.of("com.tencent."))

        // --- near-misses: a DIFFERENT app must not be captured ---
        // QQ International is a separate application this module does not support.
        assertEquals(HostApp.UNKNOWN, HostApp.of("com.tencent.mobileqqi"))
        // The other hosts are unaffected by the QQ rule.
        assertEquals(HostApp.DOUYIN, HostApp.of("com.ss.android.ugc.aweme"))
        assertEquals(HostApp.SOUL, HostApp.of("cn.soulapp.android"))
        assertEquals(HostApp.KUAISHOU, HostApp.of("com.smile.gifmaker"))
        assertEquals(HostApp.UNKNOWN, HostApp.of("com.example.unrelated"))
    }

    /**
     * v8.0.0: the OTHER Tencent apps installed on the user's device must NOT be captured as QQ.
     *
     * ===================== WHY THIS IS THE TEST THE PREFIX RULE NEEDS =====================
     * MEASURED on the device (`adb shell pm list packages`, run for this very change):
     *
     *     package:com.tencent.mobileqq        <<< QQ
     *     package:com.tencent.qqmusic         <<< a music player
     *     package:com.tencent.qqlive          <<< a video app
     *     package:io.github.qqglassnav.replica <<< a THIRD-PARTY app that merely mentions QQ
     *
     * QQ is the first host whose vendor prefix (`com.tencent.`) is shared with LOTS of other
     * installed apps -- every earlier host pair diverged in the first two segments. So the QQ rule
     * is the first one where "just use the vendor prefix" is a plausible and WRONG simplification:
     * a `com.tencent.` rule would resolve all three packages above to QQ and install QQ's hooks into
     * a music player and a video app. The real rule is the FULL package name plus a dot.
     *
     * These assertions are written against packages that REALLY EXIST on the target device rather
     * than invented names, so they cannot drift away from the actual risk.
     */
    @Test
    fun otherTencentAppsAndThirdPartyQqLookalikesAreNeverQq() {
        // Other Tencent apps that ARE installed next to QQ on the target device.
        assertEquals(HostApp.UNKNOWN, HostApp.of("com.tencent.qqmusic"))
        assertEquals(HostApp.UNKNOWN, HostApp.of("com.tencent.qqlive"))
        // A third-party app whose NAME mentions QQ. It is somebody else's app; it must stay UNKNOWN.
        assertEquals(HostApp.UNKNOWN, HostApp.of("io.github.qqglassnav.replica"))
        // A `com.tencent.qq`-stemmed package must not be swept up either: the rule requires the whole
        // real package name, so any OTHER app under that stem stays out.
        assertEquals(HostApp.UNKNOWN, HostApp.of("com.tencent.qq"))
        assertEquals(HostApp.UNKNOWN, HostApp.of("com.tencent.qq."))
        // And a near-miss on the prefix boundary: the rule needs the trailing dot, so a package that
        // merely STARTS with the same letters without the dot is not a cloner.
        assertEquals(HostApp.UNKNOWN, HostApp.of("com.tencent.mobileqqi"))
    }

    /**
     * v8.0.0: the scope array, the host list and the package list must agree on QQ.
     *
     * WHY: three separate places declare QQ's package (`res/values/arrays.xml` for the LSPosed scope
     * UI, the manifest `<queries>` block for Android 11+ visibility, and `HostApp.QQ_PACKAGES` for
     * the runtime decision). Only the last is reachable from a JVM test, so this pins that the other
     * two were at least written with the SAME string -- a mistyped scope entry would leave the user
     * unable to tick QQ in LSPosed at all, and nothing else in the suite would notice.
     */
    @Test
    fun qqIsInTheScopeListExactlyOnce() {
        val pkgs = HostApp.scopePackages()
        assertEquals("QQ's package must appear exactly once", 1, pkgs.count { it == "com.tencent.mobileqq" })
        // And the crowded-vendor near-misses must NOT be in the scope list at all.
        assertFalse(pkgs.contains("com.tencent.qqmusic"))
        assertFalse(pkgs.contains("com.tencent.qqlive"))
        assertFalse(pkgs.contains("io.github.qqglassnav.replica"))
    }

    /** v8.0.0: `ofProcess` must answer QQ for the package, the `:` sub-processes and a clone. */
    @Test
    fun ofProcessResolvesQq() {
        assertEquals(HostApp.QQ, HostApp.ofProcess("com.tencent.mobileqq", "com.tencent.mobileqq"))

        // QQ genuinely ships sub-processes (measured: com.tencent.mobileqq:video and
        // com.tencent.mobileqq:MSF, in _tools/qq/pkgfamily.txt). LSPosed reports those against
        // packageName=com.tencent.mobileqq, so the PACKAGE must decide it -- but the process-name
        // path must agree, because a clone can report a rewritten package.
        assertEquals(HostApp.QQ, HostApp.ofProcess("com.tencent.mobileqq", "com.tencent.mobileqq:MSF"))
        assertEquals(HostApp.QQ, HostApp.ofProcess("com.tencent.mobileqq", "com.tencent.mobileqq:video"))
        assertEquals(HostApp.QQ, HostApp.ofProcess("com.unknown.clone.pkg", "com.tencent.mobileqq:MSF"))

        // A recognised package WINS over the process name -- and this is the QQ/WeChat collision again:
        // a QQ package with a WeChat-looking process must still answer QQ, and vice versa.
        assertEquals(HostApp.QQ, HostApp.ofProcess("com.tencent.mobileqq", "com.tencent.mm"))
        assertEquals(HostApp.WECHAT, HostApp.ofProcess("com.tencent.mm", "com.tencent.mobileqq"))

        // Nothing recognisable => UNKNOWN, which installs NOTHING.
        assertEquals(HostApp.UNKNOWN, HostApp.ofProcess("com.tencent.mobileqqi", "com.tencent.mobileqqi:MSF"))
    }

    /**
     * v8.0.0: QQ's identity. v8.1.0: TIM joins the same host.
     *
     * The package list is pinned EXACTLY, which is the whole point of this test: v8.1.0 added
     * `com.tencent.tim` here and this assertion failed until it was updated, so an accidental change
     * to the QQ package family is a visible edit rather than a silent one.
     */
    @Test
    fun qqIdentityIsTheQqPackage() {
        assertEquals(listOf("com.tencent.mobileqq", "com.tencent.tim"), HostApp.QQ_PACKAGES)
        assertEquals("com.tencent.mobileqq", HostApp.QQ.packages.firstOrNull())
        assertTrue(HostApp.QQ.isSupported)
        // "QQ" is ASCII, exactly like Soul's brand name -- so `say()` must leave it ALONE rather than
        // rewrite it, which is the behaviour that makes this assertion worth having.
        assertEquals("QQ", HostApp.QQ.displayName)
        assertEquals("QQ\u53f7", HostApp.QQ.idLabel)
    }

    /**
     * v8.1.0: TIM resolves to the QQ HOST, but keeps its OWN name everywhere the user can read it.
     *
     * WHY BOTH HALVES MATTER, and why they are asserted together. TIM and QQ are ONE host because they
     * share the NT chat engine and therefore the whole hook set -- so `of()` must answer [HostApp.QQ]
     * for `com.tencent.tim`, or TIM gets no hooks at all. But they are TWO products, so every label
     * must say "TIM" on a TIM phone; a version line reading 「QQ版本=4.1.0」 names an app the user does
     * not have. A test that checked only the first half would pass while the UI lied, and one that
     * checked only the second would pass while TIM was unhooked.
     */
    @Test
    fun timIsTheQqHostButKeepsItsOwnName() {
        // ---- half 1: the same host, so the same hooks ----
        assertEquals(HostApp.QQ, HostApp.of("com.tencent.tim"))
        assertEquals(HostApp.QQ, HostApp.of("com.tencent.tim.somethingelse"))
        assertEquals(HostApp.QQ, HostApp.ofProcess("com.tencent.tim", "com.tencent.tim:MSF"))

        // ---- half 2: a DIFFERENT name in every user-facing label ----
        assertEquals("TIM", HostApp.QQ.displayNameFor("com.tencent.tim"))
        assertEquals("QQ", HostApp.QQ.displayNameFor("com.tencent.mobileqq"))
        // With no package known, the host falls back to its own name rather than guessing a brand.
        assertEquals("QQ", HostApp.QQ.displayNameFor(null))
        assertEquals("QQ", HostApp.QQ.displayNameFor("com.tencent.mobileqq.somethingelse"))

        // The version badge is the line a user actually reads, so it is asserted directly.
        assertEquals("TIM 4.1.0", HostApp.label(HostApp.QQ, "4.1.0", "com.tencent.tim"))
        assertEquals("QQ 9.3.65", HostApp.label(HostApp.QQ, "9.3.65", "com.tencent.mobileqq"))

        // TIM has no account identifier of its own -- it is a QQ number underneath -- so this label
        // must NOT be invented as "TIM号", which would send the model looking for a field that does
        // not exist. Asserted because "TIM号" is the obvious-looking mistake.
        assertEquals("QQ\u53f7", HostApp.QQ.idLabelFor("com.tencent.tim"))
        assertEquals("QQ\u53f7", HostApp.QQ.idLabelFor("com.tencent.mobileqq"))
    }

    /**
     * v8.1.0: TIM must NOT have widened the QQ prefix into a bare `com.tencent.`.
     *
     * The device this was developed on has qqmusic, qqlive, weishi, tmgp.sgame, the QQ mail app and
     * more installed -- all `com.tencent.*`. A looser rule would capture every one of them and install
     * QQ's hooks into a music player. This is the same boundary the pre-v8.1.0 test drew, re-asserted
     * now that a second package was added to the family and the test could have been "fixed" by
     * loosening the rule instead of extending the list.
     */
    @Test
    fun timDidNotLoosenTheQqPrefixIntoBareTencent() {
        for (p in listOf(
            "com.tencent.qqmusic",
            "com.tencent.qqlive",
            "com.tencent.weishi",
            "com.tencent.tmgp.sgame",
            "com.tencent.androidqqmail",
            "com.tencent.mm",
        )) {
            assertFalse("$p must NOT resolve to QQ", HostApp.of(p) == HostApp.QQ)
        }
        // And the two real members still do.
        assertEquals(HostApp.QQ, HostApp.of("com.tencent.mobileqq"))
        assertEquals(HostApp.QQ, HostApp.of("com.tencent.tim"))
    }

    @Test
    fun qqSayRewritesTheWeChatWording() {
        val s = HostApp.QQ.say("我的微信号是：abc，在微信里长按")
        assertTrue("微信号 must become QQ号: $s", s.contains("我的QQ号是：abc"))
        assertTrue("微信 must become QQ: $s", s.contains("在QQ里长按"))
        assertFalse("no 微信 may survive: $s", s.contains("微信"))
    }

    @Test
    fun qqLabelRendersItsVersionBadge() {
        assertEquals("QQ 9.3.65", HostApp.label(HostApp.QQ, "9.3.65"))
        assertEquals("QQ " + HostApp.NOT_INSTALLED, HostApp.label(HostApp.QQ, null))
    }

    /**
     * v8.0.0: an ASCII brand name must NOT be rewritten by `say()`.
     *
     * WHY THIS IS NOT COVERED BY THE TESTS ABOVE: `qqSayRewritesTheWeChatWording` asserts 微信 becomes
     * "QQ", which passes whether or not `say()` treats the ASCII name specially. The failure it would
     * hide is the OPPOSITE direction: `say()` runs `s.replace(微信号, idLabel).replace(微信, displayName)`
     * for every non-WeChat host, so it can only ever REMOVE 微信 -- an ASCII display name cannot be
     * corrupted by that. This pins the actual contract: the Chinese 微信 wording is gone, and the ASCII
     * brand name survives verbatim with no escaping or doubling.
     */
    @Test
    fun qqAsciiBrandNameSurvivesSayUnchanged() {
        assertEquals("QQ", HostApp.QQ.say("微信"))
        assertEquals("QQ\u53f7", HostApp.QQ.say("微信号"))
        // Idempotent: rewriting an already-rewritten string must not double anything.
        assertEquals("QQ", HostApp.QQ.say(HostApp.QQ.say("微信")))
    }

    /**
     * v6.0.0: `ofProcess` had NO test at all before this, on any host.
     *
     * WHY IT MATTERS MORE THAN `of`: `ModuleEntry.handleLoadPackage` does not call `of` -- it calls
     * THIS, and its answer decides whether any hook is installed at all. It also carries the one
     * non-obvious rule in the whole file (strip the `:` process suffix), which is exactly the kind of
     * logic that stays untested until a clone breaks in the field. The verifier flagged its absence.
     */
    @Test
    fun ofProcessResolvesEveryHostIncludingTheCloneSuffixRule() {
        // The plain case: package and process agree.
        assertEquals(HostApp.SOUL, HostApp.ofProcess("cn.soulapp.android", "cn.soulapp.android"))
        assertEquals(HostApp.WECHAT, HostApp.ofProcess("com.tencent.mm", "com.tencent.mm"))
        assertEquals(HostApp.DOUYIN, HostApp.ofProcess("com.ss.android.ugc.aweme", "com.ss.android.ugc.aweme"))

        // WeChat's auxiliary processes carry a `:` suffix; the package already decides it.
        assertEquals(HostApp.WECHAT, HostApp.ofProcess("com.tencent.mm", "com.tencent.mm:appbrand0"))

        // THE RULE: a clone can report a rewritten PACKAGE while the PROCESS keeps the real name, so
        // the suffix is stripped from the process name and used as a second opinion.
        assertEquals(HostApp.WECHAT, HostApp.ofProcess("com.unknown.clone.pkg", "com.tencent.mm:push"))
        assertEquals(HostApp.SOUL, HostApp.ofProcess("com.unknown.clone.pkg", "cn.soulapp.android:im"))
        assertEquals(HostApp.DOUYIN, HostApp.ofProcess("com.unknown.clone.pkg", "com.ss.android.ugc.aweme:push"))

        // A package that is recognised WINS, and the process name is never consulted against it.
        assertEquals(HostApp.SOUL, HostApp.ofProcess("cn.soulapp.android", "com.tencent.mm"))

        // Nothing recognisable anywhere => UNKNOWN, which installs NOTHING. This is the safety
        // property that stops the module hooking a stranger's process.
        assertEquals(HostApp.UNKNOWN, HostApp.ofProcess("com.example.app", "com.example.app:remote"))
        assertEquals(HostApp.UNKNOWN, HostApp.ofProcess(null, null))
        assertEquals(HostApp.UNKNOWN, HostApp.ofProcess("", ""))
    }

    /**
     * v6.0.0: the multi-select requirement is a NEGATIVE one, and this pins its data half.
     *
     * The user stated Soul has no multi-select feature, so part of "do not adapt it" is that Soul must
     * never be routed into the multi-select machinery. A zero-install (`UNKNOWN`) host is the module's
     * existing guard for "do not hook this", and it is what the Douyin crash and clone bugs showed is
     * worth pinning: it is a one-line predicate whose failure is invisible in the UI.
     */
    @Test
    fun anUnrecognisedHostIsNeverSupported() {
        assertFalse(HostApp.UNKNOWN.isSupported)
        // `isSupported` is what ModuleEntry gates on; every real host must pass it.
        for (h in HostApp.REAL_HOSTS) assertTrue(h.name + " must be supported", h.isSupported)
        // And UNKNOWN must never smuggle itself into the host list the UI iterates.
        assertFalse(HostApp.REAL_HOSTS.contains(HostApp.UNKNOWN))
    }

    // ------------------------------------------------------------ the model prompt

    @Test
    fun promptNamesDouyinWhenRunningInsideDouyin() {
        HostApp.setUiOverrideForTest(null)
        // `HostApp.current` is what PromptBuilder reads; setCurrent is the per-process decision.
        HostApp.setCurrent(HostApp.DOUYIN)
        try {
            val p = PromptBuilder.build(lines(), lines()[0], ScopeMode.COUNT, myWxid = "dy_me")
            assertTrue("must call the chat a Douyin chat: $p", p.contains("以下是一段抖音聊天记录"))
            assertTrue("must label my id 抖音号: $p", p.contains("我的抖音号是：dy_me"))
            assertFalse("no 微信 may reach the model: $p", p.contains("微信"))
        } finally {
            HostApp.setCurrent(HostApp.WECHAT)
        }
    }

    @Test
    fun promptKeepsWeChatWordingInsideWeChat() {
        HostApp.setCurrent(HostApp.WECHAT)
        val p = PromptBuilder.build(lines(), lines()[0], ScopeMode.COUNT, myWxid = "wxid_me")
        assertTrue("WeChat wording must be unchanged", p.contains("以下是一段微信聊天记录"))
        assertTrue(p.contains("我的微信号是：wxid_me"))
    }

    @Test
    fun groupChatInstructionFollowsTheHost() {
        HostApp.setCurrent(HostApp.DOUYIN)
        try {
            val group = listOf(
                ChatLine(base, false, "a", speaker = "a"),
                ChatLine(base + 1, false, "b", speaker = "b"),
                ChatLine(base + 2, true, "c", speaker = "me"),
            )
            val p = PromptBuilder.build(group, group[0], ScopeMode.COUNT, myWxid = "me")
            assertTrue("group rule must say 抖音号: $p", p.contains("每个人的抖音号"))
            assertFalse(p.contains("微信"))
        } finally {
            HostApp.setCurrent(HostApp.WECHAT)
        }
    }

    @Test
    fun selectionPromptFollowsTheHost() {
        HostApp.setCurrent(HostApp.DOUYIN)
        try {
            val p = PromptBuilder.buildSelection(
                kept = listOf("他：12-01 10:00:00：在吗"),
                target = lines()[0],
                myWxid = "dy_me",
            )
            assertTrue("must say 抖音 in the persona: $p", p.contains("抖音聊天分析师"))
            assertTrue("must label my id 抖音号: $p", p.contains("我的抖音号是：dy_me"))
            assertFalse(p.contains("微信"))
        } finally {
            HostApp.setCurrent(HostApp.WECHAT)
        }
    }

    @Test
    fun followUpContextFollowsTheHost() {
        HostApp.setCurrent(HostApp.DOUYIN)
        try {
            val c = PromptBuilder.followUpContext(lines(), lines()[0], "生气 6/10", myWxid = "dy_me")
            assertTrue("must say 抖音 in the persona: $c", c.contains("抖音聊天分析师"))
            assertTrue(c.contains("我的抖音号是：dy_me"))
            assertFalse(c.contains("微信"))
        } finally {
            HostApp.setCurrent(HostApp.WECHAT)
        }
    }

    // ------------------------------------------------------------ the user-typed system prompt

    @Test
    fun theStoredDefaultPromptIsRewrittenOnlyAtRequestTime() {
        // The stored value must STAY WeChat-shaped: it is a persisted setting shared by both hosts, and
        // rewriting it at save time would freeze one host's wording into the other's config.
        val stored = com.luoliaoo.wechat.data.Config.DEFAULT_PROMPT
        assertTrue("the stored prompt keeps its WeChat wording", stored.contains("微信聊天记录"))

        // `HostApp.say` spells the prompt for the host THIS PROCESS serves, which is what OpenAiClient
        // sends. Pin the Douyin side explicitly rather than relying on a default.
        HostApp.setCurrent(HostApp.DOUYIN)
        try {
            val sent = HostApp.say(stored)
            assertTrue("the outgoing prompt says 抖音", sent.contains("抖音聊天记录"))
            assertFalse("no 微信 may reach the model", sent.contains("微信"))
        } finally {
            HostApp.setCurrent(HostApp.WECHAT)
        }

        // And unchanged on the WeChat side, byte for byte.
        assertTrue(HostApp.say(stored) == stored)
    }

    @Test
    fun aCustomPromptWithoutHostWordsPassesThroughUntouched() {
        val custom = "你是我的私人助理，只回一句话。"
        HostApp.setCurrent(HostApp.DOUYIN)
        try {
            assertEquals(custom, HostApp.say(custom))
        } finally {
            HostApp.setCurrent(HostApp.WECHAT)
        }
    }

    // ------------------------------------------------------------ v8.0.0: build identity

    /**
     * v8.0.0: `BuildInfo.VERSION_NAME` must equal the build's own `versionName`.
     *
     * ===================== WHY THIS GUARD EXISTS NOW =====================
     * `BuildInfo.kt` already DOCUMENTS the rule ("BuildInfo 与 build.gradle.kts 的
     * versionCode/versionName 必须一起改；只改一处会让「到底装的哪个构建」无法回答"), and I still broke
     * it: the QQ wiring bumped `build.gradle.kts` to 8.0.0 and left `BuildInfo` at 7.0.2, so the two
     * disagreed for a whole round. Nothing caught it -- no test, no warning, no build error.
     *
     * A rule stated only in a comment is a rule that depends on the next person reading it. This
     * turns it into a failure at the one moment it can still be fixed cheaply.
     *
     * `BuildConfig.VERSION_NAME` is GENERATED by AGP from `versionName` in build.gradle.kts, so
     * comparing the hand-written constant against it detects exactly the desync this guards.
     *
     * ================== WHY THE SUFFIX IS STRIPPED INSTEAD OF USING startsWith ==================
     * The first version of this guard asserted `generated.startsWith(BuildInfo.VERSION_NAME)`, purely to
     * tolerate the `-debug` variant suffix (`versionNameSuffix`).
     *
     * BE PRECISE ABOUT WHAT THAT COST, because it did NOT fail at its main job: it DID catch the real
     * desync (gradle 9.0.0 vs a stale "8.0.0" is a prefix mismatch and fails). The hole was narrower and
     * only opens on a TRUNCATED constant: with `VERSION_NAME = "8"` or `"8.0"` the assertion still passes
     * against "8.0.0-debug". So a partial edit to this file -- the same class of mistake the guard
     * exists to catch -- would sail through.
     *
     * `removeSuffix("-debug")` keeps the tolerance exactly as wide as the actual variant suffix and no
     * wider, so the comparison is otherwise an EXACT equality, and it is a no-op for the release build
     * whose `BuildConfig.VERSION_NAME` carries no suffix.
     * ===========================================================================================
     */
    @Test
    fun buildInfoVersionMatchesTheGradleVersionName() {
        val generated = BuildConfig.VERSION_NAME
        // Strip ONLY the known variant suffix; everything else must match exactly.
        val buildVersion = generated.removeSuffix("-debug")
        assertEquals(
            "BuildInfo.VERSION_NAME=" + com.luoliaoo.wechat.BuildInfo.VERSION_NAME +
                " but the build's own versionName is " + generated +
                " -- build.gradle.kts and BuildInfo.kt must be bumped together",
            com.luoliaoo.wechat.BuildInfo.VERSION_NAME,
            buildVersion,
        )
    }
}
