package io.github.esko997.sortie.keyboard

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager

/**
 * Sortie's optional radial keyboard: an ordinary Android IME the user enables in system settings. Left stick picks a group,
 * right stick a character or diagonal editing action; A/R1 commits it. X deletes, Y spaces, B hides (on release, or still
 * held after the long-press delay), Start runs the field's action, L2 holds shift and R2 holds symbols. Touch works through
 * [RadialKeyboardView].
 *
 * Self-contained on purpose: it runs in its own process and never touches the launcher graph, storage or Shizuku. It keeps no
 * record of what is typed; the only text it ever reads is the one or two characters before the cursor when an editor cannot
 * delete by code point.
 */
class RadialInputMethodService : InputMethodService() {
    private val model = RadialKeyboardModel()
    private var view: RadialKeyboardView? = null
    private val handler = Handler(Looper.getMainLooper())

    /** Controller keys whose down we consumed; their repeats and up are consumed too, even if the keyboard hid meanwhile. */
    private val owned = BooleanArray(OWNED_KEYS)

    /** Selection as last reported by the editor; used only to tell "delete the selection" from "delete one character". */
    private var selStart = -1
    private var selEnd = -1

    // Shift/symbols holds come from trigger keys and analog trigger axes; a controller may send both, so they are ORed.
    private var shiftKey = false
    private var shiftAxis = false
    private var symbolsKey = false
    private var symbolsAxis = false

    // Axis layout of the controller that sent the last motion event, resolved once per device.
    private var axesDevice = NO_DEVICE
    private var rightX = NO_AXIS
    private var rightY = NO_AXIS
    private val leftTriggers = IntArray(MAX_TRIGGER_AXES)
    private var leftTriggerCount = 0
    private val rightTriggers = IntArray(MAX_TRIGGER_AXES)
    private var rightTriggerCount = 0

    private var repeatKey = 0
    private val repeater = object : Runnable {
        override fun run() {
            when (repeatKey) {
                KeyEvent.KEYCODE_BUTTON_X -> backspace()
                KeyEvent.KEYCODE_BUTTON_Y -> space()
                else -> return
            }
            handler.postDelayed(this, ViewConfiguration.getKeyRepeatDelay().toLong())
        }
    }

    /** A held B hides the keyboard once its long-press timeout passes, while the button is still down; a short B hides on release. */
    private var hideOnBPending = false
    private val holdHide = Runnable {
        hideOnBPending = false
        if (isInputViewShown) requestHideSelf(0)
    }

    override fun onCreate() {
        super.onCreate()
        window?.window?.let {
            it.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            it.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
    }

    override fun onCreateInputView(): View = RadialKeyboardView(this, model, ::onAction).also {
        it.enterLabel = enterLabel(currentInputEditorInfo)
        view = it
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    // Some handheld firmware forces fullscreen IMEs regardless of onEvaluateFullscreenMode. Never create an extracted
    // editor: this keyboard overlays the real app, with only its controls painted.
    override fun onCreateExtractTextView(): View? = null

    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
        // Preserve the app's layout beneath the transparent controls. Visible insets still let Android pan a covered field.
        outInsets.contentTopInsets = window?.window?.decorView?.height ?: outInsets.contentTopInsets
    }

    /** Built-in controllers can make Android think a hardware keyboard is attached, which would hide the soft keyboard. */
    override fun onEvaluateInputViewShown(): Boolean {
        super.onEvaluateInputViewShown()
        return true
    }

    override fun onStartInput(attribute: EditorInfo, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        selStart = attribute.initialSelStart
        selEnd = attribute.initialSelEnd
        if (!restarting) {
            stopRepeat()
            clearHolds()
            model.reset(numeric = isNumeric(attribute))
        }
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        setExtractViewShown(false)
        view?.let {
            it.enterLabel = enterLabel(info)
            it.refresh()
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        stopRepeat()
        cancelHoldHide()
        clearHolds()
        super.onFinishInputView(finishingInput)
    }

    override fun onFinishInput() {
        super.onFinishInput()
        stopRepeat()
        cancelHoldHide()
        clearHolds()
        model.reset()
        selStart = -1
        selEnd = -1
        view?.refresh()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        view = null
        super.onDestroy()
    }

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        selStart = newSelStart
        selEnd = newSelEnd
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!isControllerKey(keyCode)) return super.onKeyDown(keyCode, event)
        // Repeats follow their first down: ours are swallowed (A/R1 type once, X/Y repeat on our own clock), the app's pass on.
        if (event.repeatCount > 0) return owned[keyCode] || super.onKeyDown(keyCode, event)
        // A fresh press is ours only while the keyboard shows; this also clears ownership left by an up that went elsewhere.
        owned[keyCode] = isInputViewShown
        if (!owned[keyCode]) return super.onKeyDown(keyCode, event)
        when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_R1 -> commit()
            KeyEvent.KEYCODE_BUTTON_X -> {
                backspace()
                startRepeat(keyCode)
            }
            KeyEvent.KEYCODE_BUTTON_Y -> {
                space()
                startRepeat(keyCode)
            }
            KeyEvent.KEYCODE_BUTTON_L2 -> {
                shiftKey = true
                applyHolds()
            }
            KeyEvent.KEYCODE_BUTTON_R2 -> {
                symbolsKey = true
                applyHolds()
            }
            KeyEvent.KEYCODE_BUTTON_B -> {
                hideOnBPending = true
                handler.removeCallbacks(holdHide)
                handler.postDelayed(holdHide, ViewConfiguration.getLongPressTimeout().toLong())
            }
        }
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (!isControllerKey(keyCode) || !owned[keyCode]) return super.onKeyUp(keyCode, event)
        owned[keyCode] = false
        when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y -> if (repeatKey == keyCode) stopRepeat()
            KeyEvent.KEYCODE_BUTTON_L2 -> {
                shiftKey = false
                applyHolds()
            }
            KeyEvent.KEYCODE_BUTTON_R2 -> {
                symbolsKey = false
                applyHolds()
            }
            KeyEvent.KEYCODE_BUTTON_B -> {
                // Only a short press still has its hide pending; a held B already hid, and a lifecycle change cancelled it.
                val shortPress = hideOnBPending
                cancelHoldHide()
                if (shortPress && !event.isCanceled && isInputViewShown) requestHideSelf(0)
            }
            KeyEvent.KEYCODE_BUTTON_START -> if (!event.isCanceled && isInputViewShown) editorAction()
        }
        return true
    }

    /** Own joystick motion while visible: selection and layer changes must never navigate the app behind the keyboard. */
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (!isInputViewShown || event.actionMasked != MotionEvent.ACTION_MOVE || !event.isFromSource(InputDevice.SOURCE_JOYSTICK)) {
            return super.onGenericMotionEvent(event)
        }
        resolveAxes(event)
        var changed = model.updateLeftStick(event.getAxisValue(MotionEvent.AXIS_X), event.getAxisValue(MotionEvent.AXIS_Y))
        if (rightX != NO_AXIS && model.updateRightStick(event.getAxisValue(rightX), event.getAxisValue(rightY))) changed = true
        shiftAxis = triggerHeld(event, leftTriggers, leftTriggerCount, shiftAxis)
        symbolsAxis = triggerHeld(event, rightTriggers, rightTriggerCount, symbolsAxis)
        if (applyHolds(refresh = false)) changed = true
        if (changed) view?.refresh()
        return true
    }

    private fun onAction(action: KeyboardAction) {
        when (action) {
            KeyboardAction.COMMIT -> commit()
            KeyboardAction.BACKSPACE -> backspace()
            KeyboardAction.SPACE -> space()
            KeyboardAction.ENTER -> editorAction()
            KeyboardAction.HIDE -> requestHideSelf(0)
            KeyboardAction.NEXT_IME -> nextKeyboard()
            KeyboardAction.TOGGLE_SHIFT -> if (model.toggleShift()) view?.refresh()
            KeyboardAction.TOGGLE_SYMBOLS -> if (model.toggleSymbols()) view?.refresh()
        }
    }

    private fun commit() {
        model.selectedAction?.let {
            onAction(it)
            return
        }
        val text = model.selectedCharacter
        if (text.isEmpty()) return
        currentInputConnection?.commitText(text, 1)
    }

    private fun space() {
        currentInputConnection?.commitText(" ", 1)
    }

    /** Deletes the selection, else the code point before the cursor; editors without text access get a DEL key press. */
    private fun backspace() {
        val ic = currentInputConnection ?: return
        val info = currentInputEditorInfo
        if (info == null || info.inputType == InputType.TYPE_NULL || (selStart == 0 && selEnd == 0)) {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL) // also lets token/chip fields react to backspace at the start
            return
        }
        if (selStart >= 0 && selEnd >= 0 && selStart != selEnd) {
            ic.commitText("", 1)
            selEnd = minOf(selStart, selEnd) // until the editor reports back, so a held X does not keep "deleting" nothing
            selStart = selEnd
            return
        }
        if (ic.deleteSurroundingTextInCodePoints(1, 0)) return
        // Editors that predate code-point deletion report false: delete a whole surrogate pair or one unit by hand.
        val units = backspaceLength(ic.getTextBeforeCursor(2, 0))
        if (units > 0) ic.deleteSurroundingText(units, 0) else sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
    }

    /** Start/Enter: the field's action (Search, Go, Done...) unless it asks for a plain Enter, which is a newline in multi-line text. */
    private fun editorAction() {
        val ic = currentInputConnection ?: return
        val info = currentInputEditorInfo
        val action = if (info == null) EditorInfo.IME_ACTION_NONE else actionId(info)
        when {
            action != EditorInfo.IME_ACTION_NONE -> ic.performEditorAction(action)
            info != null && isMultiLine(info) -> ic.commitText("\n", 1)
            else -> sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
        }
    }

    /** Always offers a way back: the next enabled keyboard, or the system picker when there is no obvious next one. */
    private fun nextKeyboard() {
        if (!switchToNextInputMethod(false)) getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
    }

    private fun startRepeat(keyCode: Int) {
        handler.removeCallbacks(repeater)
        repeatKey = keyCode
        handler.postDelayed(repeater, ViewConfiguration.getKeyRepeatTimeout().toLong())
    }

    private fun stopRepeat() {
        repeatKey = 0
        handler.removeCallbacks(repeater)
    }

    private fun cancelHoldHide() {
        hideOnBPending = false
        handler.removeCallbacks(holdHide)
    }

    private fun clearHolds() {
        shiftKey = false
        shiftAxis = false
        symbolsKey = false
        symbolsAxis = false
        applyHolds()
    }

    private fun applyHolds(refresh: Boolean = true): Boolean {
        var changed = model.setShiftHeld(shiftKey || shiftAxis)
        if (model.setSymbolsHeld(symbolsKey || symbolsAxis)) changed = true
        if (changed && refresh) view?.refresh()
        return changed
    }

    /**
     * Android's standard gamepad layout puts the right stick on Z/RZ and the triggers on LTRIGGER/RTRIGGER (or BRAKE/GAS);
     * some controllers instead report the right stick on RX/RY and the triggers on Z/RZ. A stick axis is centred (range
     * reaching -1); a trigger rests at 0.
     */
    private fun resolveAxes(event: MotionEvent) {
        if (event.deviceId == axesDevice) return
        axesDevice = event.deviceId
        val device = event.device
        val zStick = isStickAxis(device, MotionEvent.AXIS_Z) && isStickAxis(device, MotionEvent.AXIS_RZ)
        val rStick = !zStick && isStickAxis(device, MotionEvent.AXIS_RX) && isStickAxis(device, MotionEvent.AXIS_RY)
        rightX = if (zStick) MotionEvent.AXIS_Z else if (rStick) MotionEvent.AXIS_RX else NO_AXIS
        rightY = if (zStick) MotionEvent.AXIS_RZ else if (rStick) MotionEvent.AXIS_RY else NO_AXIS
        leftTriggerCount = 0
        rightTriggerCount = 0
        for (axis in LEFT_TRIGGER_AXES) {
            if (isTriggerAxis(device, axis) && axis != rightX && axis != rightY) leftTriggers[leftTriggerCount++] = axis
        }
        for (axis in RIGHT_TRIGGER_AXES) {
            if (isTriggerAxis(device, axis) && axis != rightX && axis != rightY) rightTriggers[rightTriggerCount++] = axis
        }
    }

    private fun isStickAxis(device: InputDevice?, axis: Int): Boolean {
        val range = device?.getMotionRange(axis, InputDevice.SOURCE_JOYSTICK) ?: return false
        return range.min < -0.5f
    }

    private fun isTriggerAxis(device: InputDevice?, axis: Int): Boolean {
        val range = device?.getMotionRange(axis, InputDevice.SOURCE_JOYSTICK) ?: return false
        return range.min > -0.5f
    }

    /** Pressed past half travel, released below the neutral threshold, so a resting finger does not chatter. */
    private fun triggerHeld(event: MotionEvent, axes: IntArray, count: Int, wasHeld: Boolean): Boolean {
        var value = 0f
        for (i in 0 until count) value = maxOf(value, event.getAxisValue(axes[i]))
        return if (wasHeld) value >= RadialKeyboardModel.RELEASE else value >= RadialKeyboardModel.ENGAGE
    }

    private fun isControllerKey(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y,
        KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_R2, KeyEvent.KEYCODE_BUTTON_START
        -> true
        else -> false
    }

    private companion object {
        const val OWNED_KEYS = 128 // every handled KEYCODE_BUTTON_* is below this
        const val NO_DEVICE = Int.MIN_VALUE
        const val NO_AXIS = -1
        const val MAX_TRIGGER_AXES = 3
        val LEFT_TRIGGER_AXES = intArrayOf(MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_BRAKE, MotionEvent.AXIS_Z)
        val RIGHT_TRIGGER_AXES = intArrayOf(MotionEvent.AXIS_RTRIGGER, MotionEvent.AXIS_GAS, MotionEvent.AXIS_RZ)

        fun isNumeric(info: EditorInfo): Boolean = when (info.inputType and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_PHONE, InputType.TYPE_CLASS_DATETIME -> true
            else -> false
        }

        fun isMultiLine(info: EditorInfo): Boolean = info.inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_TEXT &&
            info.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0

        /** The editor action Start/Enter performs, or IME_ACTION_NONE for a plain Enter. */
        fun actionId(info: EditorInfo): Int {
            if (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0) return EditorInfo.IME_ACTION_NONE
            if (info.actionLabel != null && info.actionId != 0) return info.actionId
            return when (val action = info.imeOptions and EditorInfo.IME_MASK_ACTION) {
                EditorInfo.IME_ACTION_GO, EditorInfo.IME_ACTION_SEARCH, EditorInfo.IME_ACTION_SEND,
                EditorInfo.IME_ACTION_NEXT, EditorInfo.IME_ACTION_DONE, EditorInfo.IME_ACTION_PREVIOUS
                -> action
                else -> EditorInfo.IME_ACTION_NONE
            }
        }

        fun enterLabel(info: EditorInfo?): String {
            if (info == null) return "Enter"
            val id = actionId(info)
            if (id != EditorInfo.IME_ACTION_NONE && info.actionLabel != null && id == info.actionId) return info.actionLabel.toString()
            return when (id) {
                EditorInfo.IME_ACTION_GO -> "Go"
                EditorInfo.IME_ACTION_SEARCH -> "Search"
                EditorInfo.IME_ACTION_SEND -> "Send"
                EditorInfo.IME_ACTION_NEXT -> "Next"
                EditorInfo.IME_ACTION_DONE -> "Done"
                EditorInfo.IME_ACTION_PREVIOUS -> "Previous"
                else -> "Enter"
            }
        }
    }
}
