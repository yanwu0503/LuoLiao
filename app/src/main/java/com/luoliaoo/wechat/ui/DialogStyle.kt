package com.luoliaoo.wechat.ui

import android.app.Dialog

/**
 * Shows a dialog and makes its 取消 / 确定 / 确定 buttons readable.
 *
 * item 6: the platform dialog theme draws its buttons with `?android:attr/textColorPrimary`, which on this
 * dark canvas comes out a dim grey -- "取消 确定 太淡了看不清". Every dialog in the module is created with
 * `Theme_DeviceDefault_Dialog_Alert`, so they ALL need the fix, and putting it in one place means a new
 * dialog cannot forget it.
 *
 * An extension rather than a function so a call site reads `dlg.showStyled()` -- the styling is part of
 * showing the dialog, not an extra step that can be skipped.
 *
 * NOTE: this styles the PLATFORM buttons only. Dialogs whose buttons are our own `NukeTheme` views already
 * carry the right colours and are unaffected.
 *
 * Comments are ASCII-only on purpose (see core/Log.kt).
 */
fun Dialog.showStyled() {
    show()
    NukeTheme.styleDialogButtons(this)
}
