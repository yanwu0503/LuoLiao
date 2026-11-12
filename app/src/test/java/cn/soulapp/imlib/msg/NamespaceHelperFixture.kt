package cn.soulapp.imlib.msg

/**
 * TEST FIXTURE -- a class deliberately placed in Soul's OWN message namespace.
 *
 * ============================== WHY IT MUST LIVE HERE =========================================
 *
 * `SoulMsgAccess.isMessage` used to fall back, when Soul's message base class could not be loaded, to:
 *
 *     msg.javaClass.name.startsWith("cn.soulapp.imlib.msg.")
 *
 * To prove that rule is gone, the test needs an object whose class name REALLY begins with that
 * namespace -- otherwise the old code would also have returned false and the test would pass against
 * the broken revision too (a test that cannot fail is not a test).
 *
 * This class is the faithful stand-in for `cn.soulapp.imlib.msg.b`, one of the two obfuscated helper
 * classes that VERIFIABLY share the namespace with the real `ImMessage` in Soul 6.37.0. It is not a
 * message: it derives from nothing and carries nothing. Under the old prefix rule it was accepted as
 * a message; under the corrected rule it must be rejected by name.
 *
 * Nothing outside this file's own package needs it, and it is only ever constructed by
 * `SoulMsgAccessNoGuessTest`. It is never on the main source set, so it cannot reach a device build.
 */
class NamespaceHelperFixture
