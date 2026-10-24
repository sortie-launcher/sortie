package io.github.esko997.sortie

import android.app.Activity
import android.app.AlertDialog
import android.content.DialogInterface
import android.content.pm.LauncherApps
import android.os.Bundle
import android.os.Process
import android.text.BidiFormatter
import android.widget.Toast

/**
 * Handles `ShortcutManager.requestPinShortcut` from other apps (DroidDeck's "add to home screen") while Sortie is the default
 * launcher: Android only offers pinning when HOME declares a CONFIRM_PIN_SHORTCUT activity, and the request is merely an ask from
 * the app, so nothing is pinned until the user agrees. Translucent; it shows one native dialog naming the shortcut and the
 * requesting app (label from PackageManager plus the package the framework attached to the request) with Add / Cancel, where
 * Cancel holds default focus for both touch and controller. Only Add calls [io.github.esko997.sortie.data.apps.ShortcutsRepository.accept],
 * which re-reads and revalidates the request; a successfully accepted shortcut is then opted in to the Apps screen
 * (`user.shortcuts`) and can be favourited, placed on home or matched by `apps: true` folders. Cancel, Back, leaving the activity
 * (`noHistory`) or destroying it never accepts and never touches user state. Invalid, foreign-user or expired requests show a
 * failure dialog and finish. The incoming intent is only ever passed to LauncherApps, never launched.
 */
class ShortcutPinActivity : Activity() {
    private data class Pending(val shortcutLabel: String, val appLabel: String, val pkg: String)

    private var dialog: AlertDialog? = null

    /** Set once the user's answer (or a failure) is final; guards double clicks, cancel-after-click and re-creation. */
    private var decided = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState?.getBoolean(KEY_DECIDED) == true) {
            finish()
            return
        }
        val pending = pending()
        if (pending == null) {
            fail("This shortcut request is invalid or has expired, or the app that sent it isn't installed. Nothing was added.")
        } else {
            confirm(pending)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_DECIDED, decided)
    }

    override fun onDestroy() {
        // A configuration change re-creates the activity and re-asks; any other destroy (noHistory on leave) is a cancel. Neither
        // may run the dismiss handlers, which would decide or finish on the user's behalf.
        dialog?.run {
            setOnDismissListener(null)
            setOnCancelListener(null)
            dismiss()
        }
        dialog = null
        super.onDestroy()
    }

    /** Reads the request without accepting it; null unless it is a valid shortcut request for this user from an installed app. */
    private fun pending(): Pending? = runCatching<Pending?> {
        if (intent?.action != LauncherApps.ACTION_CONFIRM_PIN_SHORTCUT) return@runCatching null
        val request = getSystemService(LauncherApps::class.java).getPinItemRequest(intent) ?: return@runCatching null
        if (request.requestType != LauncherApps.PinItemRequest.REQUEST_TYPE_SHORTCUT || !request.isValid) return@runCatching null
        val info = request.shortcutInfo ?: return@runCatching null
        if (info.userHandle != Process.myUserHandle()) return@runCatching null
        val pkg = info.`package`
        val appLabel = packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        val label = (info.shortLabel ?: info.longLabel)?.toString()?.takeIf { it.isNotBlank() } ?: info.id
        Pending(shortcutLabel = clean(label), appLabel = clean(appLabel).ifEmpty { pkg }, pkg = pkg)
    }.getOrNull()

    private fun confirm(pending: Pending) {
        val bidi = BidiFormatter.getInstance()
        val message = "“${bidi.unicodeWrap(pending.shortcutLabel)}”\n\n" +
            "Requested by ${bidi.unicodeWrap(pending.appLabel)}\n${bidi.unicodeWrap(pending.pkg)}\n\n" +
            "Add puts it in Sortie's Apps screen. Cancel adds nothing."
        show(
            AlertDialog.Builder(this, DIALOG_THEME)
                .setTitle("Add shortcut to Sortie?")
                .setMessage(message)
                .setPositiveButton("Add") { _, _ -> decide(add = true) }
                .setNegativeButton("Cancel") { _, _ -> decide(add = false) }
                .setOnCancelListener { decide(add = false) }
                // Any other dismissal (e.g. the window going away) is a cancel; after a button click this is a no-op.
                .setOnDismissListener { decide(add = false) }
                .create()
        )
        dialog?.setCanceledOnTouchOutside(false)
        // Cancel is the default: focusedByDefault makes the first D-pad press from touch mode land on it, requestFocus covers
        // controller-launched (non-touch) dialogs. An accidental A press therefore never adds.
        dialog?.getButton(DialogInterface.BUTTON_NEGATIVE)?.run {
            isFocusedByDefault = true
            requestFocus()
        }
    }

    private fun decide(add: Boolean) {
        if (decided) return
        decided = true
        if (!add) {
            finish()
            return
        }
        val graph = (application as SortieApp).graph
        val entry = graph.shortcuts.accept(intent)
        if (entry == null) {
            fail("The app withdrew this shortcut or the request expired. Nothing was added; ask the app to add it again.")
            return
        }
        graph.user.showShortcut(entry.id, true)
        Toast.makeText(applicationContext, "Added to Sortie: ${entry.label}", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun fail(message: String) {
        decided = true
        show(
            AlertDialog.Builder(this, DIALOG_THEME)
                .setTitle("Couldn't add shortcut")
                .setMessage(message)
                .setPositiveButton("OK", null)
                .setOnDismissListener { finish() }
                .create()
        )
        dialog?.getButton(DialogInterface.BUTTON_POSITIVE)?.run {
            isFocusedByDefault = true
            requestFocus()
        }
    }

    /** Shows [next] as the activity's only dialog; a dialog it replaces is dismissing itself (button click) or already gone. */
    private fun show(next: AlertDialog) {
        dialog = next
        if (!isFinishing && !isDestroyed) next.show()
    }

    companion object {
        private const val KEY_DECIDED = "decided"
        private const val DIALOG_THEME = android.R.style.Theme_DeviceDefault_Dialog_Alert
        private const val MAX_LABEL = 120

        /** One line, bounded: labels come from the requesting app and must not fake extra lines of the prompt. */
        private fun clean(text: String): String = text.replace(Regex("[\\s\\p{Z}\\p{Cc}]+"), " ").trim().take(MAX_LABEL)
    }
}
