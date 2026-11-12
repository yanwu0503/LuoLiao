package com.tencent.mobileqq.aio.msg

import com.tencent.qqnt.kernel.nativeinterface.MsgRecord

/**
 * A TEST FIXTURE that occupies the exact name of QQ's real UI item, `AIOMsgItem` [classes3.dex].
 *
 * ============================== WHY THE NAME MUST BE EXACT =============================
 *
 * The UI path does NOT hand a hook the record. It hands the ITEM, and the item carries the record.
 * So [com.luoliaoo.wechat.hook.QQMsgAccess.unwrap] must accept the WRAPPER, and if that route were
 * broken every message read off the UI would come back empty while the LOGS LOOKED HEALTHY -- the exact
 * failure mode this project's 必看.md keeps recording. A fixture under any other name would never be
 * reached by the reader's reflective lookup, so the test would still pass with the wrapper route
 * deleted. Hence the exact name, in test sources.
 *
 * ============================== `o()` IS A TRAP, AND IT IS DELIBERATE ==================
 *
 * The real class was disassembled (verifying script: `_tools/qq/t14_field_write_scan.py`):
 *
 *     field  i1  acc=0x001a  STATIC   MsgRecord     <-- written ONCE in <clinit>
 *     field  e   (instance)  MsgRecord              <-- written in <init>, per message
 *     method o()            acc=0x1019  STATIC + FINAL + SYNTHETIC   -> sget-object i1
 *     method getMsgRecord() acc=0x0011  INSTANCE (public final)      -> iget-object  e
 *
 * `o()` looks like a second record accessor and is NOT one. It is a Kotlin **synthetic bridge** whose
 * body is `sget-object i1`: it reads a STATIC field assigned once in `<clinit>`, so it returns the SAME
 * object for every message in the process -- it cannot identify "this" message at all. `i1` is the
 * default-argument placeholder name Kotlin generates.
 *
 * WHY THIS FIXTURE REPRODUCES IT RATHER THAN OMITTING IT: an earlier revision of the reader listed BOTH
 * `getMsgRecord` and `o` as candidates. That failed **dangerously rather than safely** -- `o()` does not
 * throw or return null, it returns a well-formed, empty, SHARED record, so every downstream type check
 * still passed. Two silent, device-visible consequences: analysis showed blank text, and history keys
 * collapsed (an empty record's `getMsgId()` is 0 for every message, so every line in a conversation
 * mapped to the same key and all but one were dropped).
 *
 * A bug that silently poisons the data is worth a standing test, so [o] here returns a POISON record
 * carrying a marker body. If anyone ever adds `o` back to the candidate list, the assertions in
 * `QQMsgAccessTest` fail loudly instead of shipping blank analysis.
 *
 * ============================== WHAT IT MODELS, AND WHAT IT DOES NOT ==========================
 *
 * MODELLED: the instance record field `e` (real name), QQ's real `getMsgRecord()`, and the static
 * `o()` trap with faithful modifiers (`@JvmStatic` in a companion == static+synthetic bridge).
 *
 * NOT MODELLED: the other ~88 fields of the real item (bubble drawings, nick components, skin info,
 * avatar objects). They are UI state and no reader path touches them, so reproducing them would add
 * noise without adding evidence. The on-device probe confirms the real item's shape.
 *
 * Comments ASCII-only (see core/Log.kt). Test sources only; it never ships.
 */
@Suppress("unused")
open class AIOMsgItem {

    /**
     * The record. Field NAME `e` matches the real class, so a reader that read the FIELD instead of the
     * getter would also succeed -- and both routes are exercised.
     */
    @JvmField
    var e: MsgRecord? = null

    /** QQ's real accessor: public, final, INSTANCE -- and the only route that can work. */
    fun getMsgRecord(): MsgRecord? = e

    companion object {
        /**
         * The STATIC field the real `o()` reads. Modelled so the trap is faithful: static, and set once
         * per class load, exactly like the real `i1` (written in `<clinit>`).
         */
        @JvmField
        var i1: MsgRecord? = null

        /**
         * The trap. `@JvmStatic` makes this a STATIC method, matching the real `o()`'s `acc=0x1019`
         * (static + final + synthetic). Returns [i1] -- i.e. the SAME object for every item, never the
         * receiver's record -- which is precisely the failure mode being guarded against.
         */
        @JvmStatic
        fun o(): MsgRecord? = i1
    }
}
