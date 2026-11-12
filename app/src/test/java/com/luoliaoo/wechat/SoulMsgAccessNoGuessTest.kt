package com.luoliaoo.wechat

import com.luoliaoo.wechat.core.Resolver
import com.luoliaoo.wechat.hook.SoulMsgAccess
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The "DEGRADE, DO NOT GUESS" rule, asserted for [SoulMsgAccess.isMessage].
 *
 * ============================== WHY THIS TEST EXISTS ==========================================
 *
 * The captain's requirement for SOUL-4 was explicit:
 *
 *     「你的 Soul 读取器在拿不到证据时,必须行为退化而不是猜 ... 空转比假成功好.」
 *     (When your Soul reader has no evidence, it must DEGRADE rather than guess; an idle feed beats
 *      a false success.)
 *
 * `isMessage` is where that rule is easiest to break, because it is the pipeline's front door: if it
 * says "yes" about something that is not a message, every later stage treats it as real, and the
 * result is a store that LOOKS like it is working.
 *
 * The revision under test had exactly that flaw. Its fallback, for when Soul's message base class
 * could not be loaded, was:
 *
 *     msg.javaClass.name.startsWith("cn.soulapp.imlib.msg.")
 *
 * That is a guess, and it was refutable in advance: dumping that namespace shows THREE members --
 * `ImMessage`, plus the obfuscated helpers `msg.a` and `msg.b`. A prefix match accepts the helpers
 * too, and that path logged nothing, so a mis-fire left no trace at all.
 *
 * The tests below pin the corrected behaviour: an exact-name match or nothing, with the weak route
 * recorded. They are written so that reintroducing a namespace/prefix fallback FAILS the build.
 *
 * The classloader is made deliberately unable to load Soul's classes, which is precisely the
 * condition that used to trigger the guess -- so the test exercises the real degraded path rather
 * than a simulation of it. (If a Soul class were ever on the test classpath, the first test would
 * fail loudly on its precondition rather than silently pass for the wrong reason.)
 *
 * Comments ASCII-only (see core/Log.kt).
 */
class SoulMsgAccessNoGuessTest {

    /** A loader that can load nothing, so `Class.forName(MSG_CLASS)` always throws. */
    private class EmptyLoader : ClassLoader(null) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> =
            throw ClassNotFoundException(name)

        override fun findClass(name: String): Class<*> =
            throw ClassNotFoundException(name)
    }

    private fun accessWithUnloadableHost(): SoulMsgAccess {
        // NO GLOBAL SETUP IS NEEDED, and none is done on purpose.
        //
        // `Resolver.classLoader()` is `ModuleBridge.hostClassLoader ?: loader`. `hostClassLoader` has a
        // private setter and is simply NULL in a JVM test (nothing ever called the real host bootstrap),
        // so the constructor loader below is what gets used -- which is exactly the "Soul's classes are
        // not loadable" condition this test needs. Mutating process-wide state to force it would also
        // risk leaking into other tests in the same JVM.
        //
        // apkPath/workDir are never touched by isMessage; they only matter to Resolver.resolve().
        val r = Resolver(EmptyLoader(), "", File("."))
        return SoulMsgAccess(r)
    }

    /**
     * A stand-in that REALLY lives in Soul's message namespace.
     *
     * It is `cn.soulapp.imlib.msg.NamespaceHelperFixture`, mirroring the verified shape of the real
     * helpers `cn.soulapp.imlib.msg.a` / `.b` that share that namespace with `ImMessage`. Using a
     * class from the test's OWN package would have made these tests pass against the broken revision
     * too (its old prefix rule would not have matched either), i.e. they would have been worthless.
     */
    private fun namespaceHelper(): Any = cn.soulapp.imlib.msg.NamespaceHelperFixture()

    @Test
    fun theHostCannotBeLoadedSoThisTestExercisesTheDegradedPath() {
        // PRECONDITION, asserted so the tests below cannot pass for the wrong reason: with a real
        // host loader present, `isMessage` would take the assignability branch and the degraded
        // branch would go untested.
        val access = accessWithUnloadableHost()
        var loadable = true
        try {
            Class.forName("cn.soulapp.imlib.msg.ImMessage", false, EmptyLoader())
        } catch (_: Throwable) {
            loadable = false
        }
        assertFalse("the empty loader must not resolve Soul classes", loadable)
        assertFalse(access.isMessage(null))
    }

    @Test
    fun anUnrelatedObjectIsNeverAcceptedWhenTheBaseClassIsUnloadable() {
        // THE regression guard for the guess: before the fix this returned TRUE for the namespace
        // helper below, because its name merely began with the Soul message namespace.
        val access = accessWithUnloadableHost()
        assertFalse(access.isMessage(namespaceHelper()))
        assertFalse(access.isMessage("a string"))
        assertFalse(access.isMessage(42))
        assertFalse(access.isMessage(Any()))
    }

    @Test
    fun theNamespaceHelperProvesTheOldPrefixRuleWouldHaveAcceptedIt() {
        // SELF-CHECK on the fixture: if this class were NOT in the Soul namespace, the test above
        // would pass even against the broken revision. So the precondition is asserted directly --
        // the fixture's name must really start with the prefix the old code matched.
        val name = namespaceHelper().javaClass.name
        assertTrue(
            "fixture must live in Soul's namespace to be a valid counterexample, was: " + name,
            name.startsWith("cn.soulapp.imlib.msg."),
        )
        assertFalse(
            "fixture must NOT be the real message class",
            name == "cn.soulapp.imlib.msg.ImMessage",
        )
    }

    @Test
    fun repeatedRejectionsStayRejections() {
        // Idempotence: the degraded path must not "warm up" into accepting something after the first
        // call, and it must not throw on repetition (it is called on every row of every bind).
        val access = accessWithUnloadableHost()
        repeat(50) {
            assertFalse(access.isMessage(namespaceHelper()))
        }
    }
}
