package io.github.esko997.sortie.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Bundle
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Touch and controller surface of the radial keyboard, drawn natively so the IME needs no Compose lifecycle scaffolding.
 *
 * Left wheel: the eight character groups. Each group is drawn as a small up/right/down/left diamond of its four characters,
 * mirroring the right wheel, so a group shows where each character will sit before it is picked. The right wheel has four
 * cardinal characters and four diagonal editing actions; its centre previews what A will do. Both sticks use eight sectors.
 * The centre of the screen stays clear, with only switch-keyboard, field-action and hide buttons above the wheels.
 *
 * The view never touches the input connection: it selects through [model] and reports everything else via [onAction]. The
 * service calls [refresh] after controller input changes the model. Geometry, paints and paths are built at layout time;
 * drawing and touch handling do not allocate. Accessibility uses a framework [AccessibilityNodeProvider] (one virtual node per
 * group, character and key) so no extra dependency is needed.
 */
@SuppressLint("ViewConstructor")
class RadialKeyboardView(
    context: Context,
    private val model: RadialKeyboardModel,
    private val onAction: (KeyboardAction) -> Unit
) : View(context) {

    /** Label of the field action key (Search, Go, Done...). Blank falls back to "Enter". */
    var enterLabel: String = DEFAULT_ENTER
        set(value) {
            val label = value.ifBlank { DEFAULT_ENTER }
            if (label == field) return
            field = label
            if (width > 0) {
                fitButton(B_ENTER)
                invalidate()
            }
        }

    /** Shows the controller button badges (A, X, Y, B, Start, L2, R2, LS) where they fit. */
    var controllerHints: Boolean = true
        set(value) {
            if (value == field) return
            field = value
            if (width > 0) {
                layoutGeometry(width, height)
                invalidate()
            }
        }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = AMBER
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
    }

    /** Monospace bold keeps look-alikes apart: l I 1 |, O 0, ' ` on the wheels. */
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val fontMetrics = Paint.FontMetrics()
    private val labelRatio = baselineRatio(labelPaint)
    private val glyphRatio = baselineRatio(glyphPaint)
    private val badgeRatio = baselineRatio(badgePaint)

    // Metrics (px), refreshed from the display and font scale on every measure.
    private var border = 1f
    private var pad = 0f
    private var gap = 0f
    private var headerH = 0f
    private var headerInset = 0f
    private var minTouch = 0f
    private var inset = 0f
    private var iconGap = 0f
    private var iconSize = 0f
    private var corner = 0f
    private var labelPref = 0f
    private var badgeH = 0f
    private var badgePadX = 0f
    private var badgeGap = 0f
    private var badgeCorner = 0f
    private var pipTextPref = 0f
    private var charTextPref = 0f
    private var centerTextPref = 0f
    private var layerTextPref = 0f
    private var dMin = 0f
    private var dMax = 0f
    private var heightShare = 0f

    // Wheel geometry.
    private var wheelD = 0f
    private var wheelR = 0f
    private var leftCx = 0f
    private var leftCy = 0f
    private var rightCx = 0f
    private var rightCy = 0f
    private var leftRingR = 0f
    private var leftPipR = 0f
    private var leftCenterR = 0f
    private var rightRingR = 0f
    private var rightPipR = 0f
    private var rightCenterR = 0f
    private val groupX = FloatArray(GROUPS)
    private val groupY = FloatArray(GROUPS)
    private val charX = FloatArray(SLOTS)
    private val charY = FloatArray(SLOTS)
    private var pipTextSize = 0f
    private var pipOffset = 0f
    private var charTextSize = 0f
    private var centerCharSize = 0f
    private var centerCharCy = 0f
    private var layerCy = 0f
    private val leftArc = RectF()
    private val rightArc = RectF()
    private val leftBadge = RectF()
    private val rightBadge = RectF()
    private var showLeftBadge = false
    private var showRightBadge = false

    // Keys, indexed by B_*.
    private val btnRect = Array(B_COUNT) { RectF() }
    private val btnHit = Array(B_COUNT) { RectF() }
    private val btnBadge = Array(B_COUNT) { RectF() }
    private val btnIcon = Array(B_COUNT) { Path() }
    private val btnTextSize = FloatArray(B_COUNT)
    private val btnLabelX = FloatArray(B_COUNT)
    private val btnShowLabel = BooleanArray(B_COUNT)
    private val btnShowIcon = BooleanArray(B_COUNT)
    private val btnShowBadge = BooleanArray(B_COUNT)

    // Touch, per pointer id.
    private val pointerKind = IntArray(MAX_POINTERS)
    private val pointerTarget = IntArray(MAX_POINTERS) { T_NONE }
    private var pressedMask = 0
    private val repeatDelete = object : Runnable {
        override fun run() {
            if (!held(T_BACKSPACE)) return
            onAction(KeyboardAction.BACKSPACE)
            postDelayed(this, REPEAT_INTERVAL_MS)
        }
    }

    // Accessibility.
    private val a11y: AccessibilityManager? = context.getSystemService(AccessibilityManager::class.java)
    private val provider = KeyProvider()
    private var a11yFocus = T_NONE
    private var hoverTarget = T_NONE
    private var spokenGroup = Int.MIN_VALUE
    private var spokenIndex = Int.MIN_VALUE
    private var spokenLayer: String? = null
    private val screenLoc = IntArray(2)
    private val boundsF = RectF()
    private val bounds = Rect()

    init {
        contentDescription = "Radial keyboard"
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    /** Redraws the current model state and voices selection or layer changes to screen readers. */
    fun refresh() {
        invalidate()
        val group = model.selectedGroup
        val index = model.selectedSector
        val layer = model.layerLabel
        if (a11y?.isEnabled == true) {
            val message = when {
                spokenLayer != null && layer != spokenLayer -> layerName()
                group != spokenGroup && group in 0 until GROUPS -> groupDescription(group)
                index != spokenIndex && index in 0 until RadialKeyboardModel.RIGHT_SECTORS -> spoken(commitLabel())
                else -> null
            }
            if (message != null) announceForAccessibility(message)
            sendContentChanged()
        }
        spokenGroup = group
        spokenIndex = index
        spokenLayer = layer
    }

    // region Measure and layout

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val dm = resources.displayMetrics
        val w = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) dm.widthPixels else MeasureSpec.getSize(widthMeasureSpec)
        updateMetrics(dm)
        planWheels(w, dm)
        val body = wheelD
        setMeasuredDimension(w, resolveSize(ceil(border + headerH + body + pad).toInt(), heightMeasureSpec))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layoutGeometry(w, h)
    }

    override fun onConfigurationChanged(newConfig: Configuration?) {
        super.onConfigurationChanged(newConfig)
        requestLayout()
    }

    private fun sp(value: Float, dm: DisplayMetrics) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, dm)

    /** Everything text-bearing scales with the user's font size; touch targets never drop below 48 dp. */
    private fun updateMetrics(dm: DisplayMetrics) {
        val dp = dm.density
        border = max(1f, dp)
        pad = 10 * dp
        gap = 8 * dp
        minTouch = 48 * dp
        labelPref = sp(15f, dm)
        val fontScale = labelPref / (15 * dp)
        headerH = max(44 * dp, labelPref * 2.4f)
        headerInset = 4 * dp
        inset = 10 * dp
        iconGap = 8 * dp
        iconSize = labelPref * 1.25f
        corner = 12 * dp
        badgePaint.textSize = sp(11f, dm)
        badgeH = badgePaint.textSize * 1.3f + 2 * dp
        badgePadX = 5 * dp
        badgeGap = 6 * dp
        badgeCorner = 4 * dp
        pipTextPref = sp(13f, dm)
        charTextPref = sp(24f, dm)
        centerTextPref = sp(36f, dm)
        layerTextPref = sp(15f, dm)
        val ideal = pipTextPref * 15.5f
        dMin = ideal * 0.6f
        dMax = ideal * 1.25f
        // Larger text may claim a little more of the screen, never more than 60% in landscape.
        heightShare = min(fontScale, 1.2f).coerceAtLeast(1f)
        iconPaint.strokeWidth = max(1.5f * dp, iconSize * 0.09f)
        arcPaint.strokeWidth = 3 * dp
    }

    /** Leave the app visible above and between the wheels; keep the character groups readable on smaller displays. */
    private fun planWheels(w: Int, dm: DisplayMetrics) {
        val landscape = dm.widthPixels > dm.heightPixels
        val budget = dm.heightPixels * (if (landscape) LANDSCAPE_SHARE else PORTRAIT_SHARE) * heightShare
        val fixed = border + headerH + pad
        wheelD = min((budget - fixed).coerceIn(dMin, dMax), (w - 2 * pad - gap) / 2).coerceAtLeast(0f)
    }

    private fun layoutGeometry(w: Int, h: Int) {
        val dm = resources.displayMetrics
        updateMetrics(dm)
        planWheels(w, dm)
        val wf = w.toFloat()
        val bodyTop = border + headerH
        val bodyH = max(h - bodyTop - pad, 0f)
        wheelD = min(wheelD, bodyH).coerceAtLeast(0f)
        wheelR = wheelD / 2
        layoutHeader(wf)
        leftCx = pad + wheelR
        rightCx = wf - pad - wheelR
        leftCy = bodyTop + wheelR
        rightCy = leftCy
        layoutWheels()
        for (i in DIAGONAL_BUTTONS.indices) {
            val b = DIAGONAL_BUTTONS[i]
            val angle = (i * 2 + 1) * TAU / RadialKeyboardModel.RIGHT_SECTORS
            val x = rightCx + rightRingR * sin(angle)
            val y = rightCy - rightRingR * cos(angle)
            btnRect[b].set(x - rightPipR, y - rightPipR, x + rightPipR, y + rightPipR)
            btnHit[b].set(btnRect[b])
        }
        for (b in 0 until B_COUNT) fitButton(b)
    }

    /** Header keys hug their content; their hit areas span the full header height and at least 48 dp. */
    private fun layoutHeader(wf: Float) {
        val top = border
        val bottom = border + headerH
        val limit = wf / 3 - pad - gap
        val nextW = min(naturalWidth(B_NEXT), limit).coerceAtLeast(min(minTouch, limit))
        val hideW = min(naturalWidth(B_HIDE), limit).coerceAtLeast(min(minTouch, limit))
        btnRect[B_NEXT].set(pad, top + headerInset, pad + nextW, bottom - headerInset)
        btnRect[B_HIDE].set(wf - pad - hideW, top + headerInset, wf - pad, bottom - headerInset)
        val enterW = min(naturalWidth(B_ENTER), limit).coerceAtLeast(min(minTouch, limit))
        btnRect[B_ENTER].set((wf - enterW) / 2, top + headerInset, (wf + enterW) / 2, bottom - headerInset)
        btnHit[B_ENTER].set(btnRect[B_ENTER])
        for (b in B_HIDE..B_NEXT) {
            val r = btnRect[b]
            val grow = max(gap / 2, (minTouch - r.width()) / 2)
            btnHit[b].set(r.left - grow, top, r.right + grow, bottom)
        }
    }

    private fun layoutWheels() {
        val r = wheelR
        leftPipR = r * 0.265f
        leftRingR = r - leftPipR
        leftCenterR = leftRingR - leftPipR - r * 0.06f
        rightPipR = r * 0.265f
        rightRingR = r - rightPipR
        rightCenterR = rightRingR - rightPipR - r * 0.05f
        for (g in 0 until GROUPS) {
            val a = g * TAU / GROUPS
            groupX[g] = leftCx + leftRingR * sin(a)
            groupY[g] = leftCy - leftRingR * cos(a)
        }
        for (i in 0 until SLOTS) {
            val a = i * TAU / SLOTS
            charX[i] = rightCx + rightRingR * sin(a)
            charY[i] = rightCy - rightRingR * cos(a)
        }
        pipTextSize = min(pipTextPref, leftPipR * 0.52f)
        pipOffset = leftPipR * 0.47f
        charTextSize = min(charTextPref, rightPipR * 0.95f)
        val half = arcPaint.strokeWidth / 2
        leftArc.set(leftCx - leftCenterR + half, leftCy - leftCenterR + half, leftCx + leftCenterR - half, leftCy + leftCenterR - half)
        rightArc.set(rightCx - rightCenterR + half, rightCy - rightCenterR + half, rightCx + rightCenterR - half, rightCy + rightCenterR - half)

        // Left disc: layer label, with the left-stick badge underneath when both fit.
        val layerH = layerTextPref * 1.2f
        val leftBlock = layerH + gap / 2 + badgeH
        showLeftBadge = controllerHints && leftBlock <= leftCenterR * 1.5f
        if (showLeftBadge) {
            val top = leftCy - leftBlock / 2
            layerCy = top + layerH / 2
            setBadge(leftBadge, leftCx, top + leftBlock - badgeH / 2, BADGE_LEFT_STICK)
        } else {
            layerCy = leftCy
        }

        // Right disc: preview of the character A types, with the A badge underneath when both fit.
        val room = rightCenterR * 1.6f
        centerCharSize = min(centerTextPref, rightCenterR * 0.95f)
        showRightBadge = controllerHints && room - badgeH - gap / 2 >= centerTextPref * 0.5f * 1.05f
        if (showRightBadge) {
            centerCharSize = min(centerCharSize, (room - badgeH - gap / 2) / 1.05f)
            val charH = centerCharSize * 1.05f
            val block = charH + gap / 2 + badgeH
            val top = rightCy - block / 2
            centerCharCy = top + charH / 2
            setBadge(rightBadge, rightCx, top + block - badgeH / 2, BADGE_COMMIT)
        } else {
            centerCharCy = rightCy
        }
    }

    private fun setBadge(out: RectF, cx: Float, cy: Float, text: String) {
        val w = badgePaint.measureText(text) + 2 * badgePadX
        out.set(cx - w / 2, cy - badgeH / 2, cx + w / 2, cy + badgeH / 2)
    }

    private fun naturalWidth(b: Int): Float {
        val label = keyLabel(b)
        val hint = if (controllerHints) KEY_HINTS[b] else null
        val hasIcon = hasIcon(b)
        labelPaint.textSize = labelPref
        var w = 2 * inset
        if (hasIcon) w += iconSize
        if (label != null) w += labelPaint.measureText(label) + if (hasIcon) iconGap else 0f
        if (hint != null) w += badgeGap + badgePaint.measureText(hint) + 2 * badgePadX
        return w
    }

    /**
     * Fits icon, label and controller badge inside a key. Gives up the badge first, then shrinks the label to 80%, then drops
     * the label when an icon can stand alone, and only then shrinks text further.
     */
    private fun fitButton(b: Int) {
        val r = btnRect[b]
        val label = keyLabel(b)
        val hint = if (controllerHints) KEY_HINTS[b] else null
        var showIcon = hasIcon(b)
        var showLabel = label != null
        var showBadge = hint != null
        if (r.width() <= 0f) {
            btnShowIcon[b] = false
            btnShowLabel[b] = false
            btnShowBadge[b] = false
            return
        }
        val avail = r.width() - 2 * inset
        labelPaint.textSize = labelPref
        var labelW = if (label != null) labelPaint.measureText(label) else 0f
        val badgeW = if (hint != null) badgePaint.measureText(hint) + 2 * badgePadX else 0f
        var size = labelPref
        val iconPart = when {
            !showIcon -> 0f
            showLabel -> iconSize + iconGap
            else -> iconSize
        }
        if (showBadge && iconPart + labelW + badgeGap + badgeW > avail) showBadge = false
        if (showLabel && iconPart + labelW > avail) {
            val room = max(avail - iconPart, 0f)
            when {
                room >= labelW * 0.8f -> {
                    size = labelPref * room / labelW
                    labelW = room
                }
                showIcon -> showLabel = false
                else -> {
                    size = labelPref * max(avail, 0f) / labelW
                    labelW = max(avail, 0f)
                }
            }
        }
        if (showIcon && iconSize > avail + 2 * inset) showIcon = false
        val iconW = if (showIcon) iconSize else 0f
        val iconSpace = if (showIcon && showLabel) iconGap else 0f
        val textW = if (showLabel) labelW else 0f
        val badgeSpace = if (showBadge) badgeGap + badgeW else 0f
        val start = r.centerX() - (iconW + iconSpace + textW + badgeSpace) / 2
        val cy = r.centerY()
        if (showIcon) buildIcon(b, start + iconW / 2, cy, iconSize)
        btnLabelX[b] = start + iconW + iconSpace + textW / 2
        btnTextSize[b] = size
        if (showBadge) {
            val left = start + iconW + iconSpace + textW + badgeGap
            btnBadge[b].set(left, cy - badgeH / 2, left + badgeW, cy + badgeH / 2)
        }
        btnShowIcon[b] = showIcon
        btnShowLabel[b] = showLabel
        btnShowBadge[b] = showBadge
    }

    private fun buildIcon(b: Int, cx: Float, cy: Float, s: Float) {
        val p = btnIcon[b]
        p.reset()
        when (b) {
            B_BACKSPACE -> {
                val h = s * 0.36f
                val l = cx - s / 2
                val r = cx + s / 2
                p.moveTo(l, cy)
                p.lineTo(l + s * 0.3f, cy - h)
                p.lineTo(r, cy - h)
                p.lineTo(r, cy + h)
                p.lineTo(l + s * 0.3f, cy + h)
                p.close()
                val xc = cx + s * 0.12f
                val k = s * 0.13f
                p.moveTo(xc - k, cy - k)
                p.lineTo(xc + k, cy + k)
                p.moveTo(xc + k, cy - k)
                p.lineTo(xc - k, cy + k)
            }
            B_HIDE -> {
                p.moveTo(cx - s * 0.35f, cy - s * 0.15f)
                p.lineTo(cx, cy + s * 0.2f)
                p.lineTo(cx + s * 0.35f, cy - s * 0.15f)
            }
            B_NEXT -> {
                val r = s * 0.44f
                p.addCircle(cx, cy, r, Path.Direction.CW)
                p.addOval(cx - r * 0.45f, cy - r, cx + r * 0.45f, cy + r, Path.Direction.CW)
                p.moveTo(cx - r, cy)
                p.lineTo(cx + r, cy)
            }
        }
    }

    // endregion

    // region Drawing

    override fun onDraw(canvas: Canvas) {
        if (wheelR <= 0f) return
        strokePaint.strokeWidth = border
        drawLeftWheel(canvas)
        drawRightWheel(canvas)
        for (b in 0 until B_COUNT) drawKey(canvas, b)
    }

    private fun drawLeftWheel(canvas: Canvas) {
        strokePaint.color = LINE
        canvas.drawCircle(leftCx, leftCy, leftRingR, strokePaint)
        fillPaint.color = DEEP
        canvas.drawCircle(leftCx, leftCy, leftCenterR, fillPaint)
        canvas.drawCircle(leftCx, leftCy, leftCenterR, strokePaint)
        val selected = model.selectedGroup
        if (selected in 0 until GROUPS) canvas.drawArc(leftArc, selected * 45f - 90f - ARC_SWEEP / 2, ARC_SWEEP, false, arcPaint)

        val labels = model.groupLabels
        val baseline = pipTextSize * glyphRatio
        for (g in 0 until GROUPS) {
            val x = groupX[g]
            val y = groupY[g]
            val on = g == selected
            fillPaint.color = if (on) AMBER else SLATE
            canvas.drawCircle(x, y, leftPipR, fillPaint)
            if (!on) canvas.drawCircle(x, y, leftPipR, strokePaint)
            val text = if (g < labels.size) labels[g] else continue
            glyphPaint.color = if (on) INK else PIP_TEXT
            glyphPaint.textSize = pipTextSize
            if (text.length == SLOTS) {
                canvas.drawText(text, 0, 1, x, y - pipOffset + baseline, glyphPaint)
                canvas.drawText(text, 1, 2, x + pipOffset, y + baseline, glyphPaint)
                canvas.drawText(text, 2, 3, x, y + pipOffset + baseline, glyphPaint)
                canvas.drawText(text, 3, 4, x - pipOffset, y + baseline, glyphPaint)
            } else {
                drawFitted(canvas, text, x, y, leftPipR * 1.6f, pipTextSize, glyphPaint, glyphRatio)
            }
        }

        labelPaint.color = if (model.shifted || model.symbols) AMBER else TEXT
        drawFitted(canvas, model.layerLabel, leftCx, layerCy, leftCenterR * 1.5f, layerTextPref, labelPaint, labelRatio)
        if (showLeftBadge) drawBadge(canvas, leftBadge, BADGE_LEFT_STICK, false)
    }

    private fun drawRightWheel(canvas: Canvas) {
        strokePaint.color = LINE
        canvas.drawCircle(rightCx, rightCy, rightRingR, strokePaint)
        fillPaint.color = if (held(T_COMMIT)) SLATE_HI else DEEP
        canvas.drawCircle(rightCx, rightCy, rightCenterR, fillPaint)
        canvas.drawCircle(rightCx, rightCy, rightCenterR, strokePaint)
        val selected = model.selectedIndex
        val sector = model.selectedSector
        if (sector in 0 until RadialKeyboardModel.RIGHT_SECTORS) {
            canvas.drawArc(rightArc, sector * 45f - 90f - ARC_SWEEP / 2, ARC_SWEEP, false, arcPaint)
        }

        val chars = model.characterLabels
        for (i in 0 until SLOTS) {
            val on = i == selected
            fillPaint.color = if (on) AMBER else SLATE
            canvas.drawCircle(charX[i], charY[i], rightPipR, fillPaint)
            if (!on) canvas.drawCircle(charX[i], charY[i], rightPipR, strokePaint)
            if (i >= chars.size || chars[i].isEmpty()) continue
            glyphPaint.color = if (on) INK else TEXT
            drawFitted(canvas, chars[i], charX[i], charY[i], rightPipR * 1.5f, charTextSize, glyphPaint, glyphRatio)
        }

        val current = commitLabel()
        if (current.isNotEmpty()) {
            glyphPaint.color = TEXT
            drawFitted(canvas, current, rightCx, centerCharCy, rightCenterR * 1.4f, centerCharSize, glyphPaint, glyphRatio)
            if (showRightBadge) drawBadge(canvas, rightBadge, BADGE_COMMIT, false)
        }
    }

    private fun drawKey(canvas: Canvas, b: Int) {
        val r = btnRect[b]
        if (r.width() <= 0f) return
        val pressed = held(T_SHIFT + b)
        val active = selectedActionButton() == b || (b == B_SHIFT && model.shifted) || (b == B_SYMBOLS && model.symbols)
        val header = b == B_HIDE || b == B_NEXT
        val fill = when {
            active -> if (pressed) AMBER_DOWN else AMBER
            pressed -> SLATE_HI
            else -> SLATE
        }
        val radius = if (b < B_ENTER) r.width() / 2 else min(corner, r.height() / 4)
        if (fill != 0) {
            fillPaint.color = fill
            canvas.drawRoundRect(r, radius, radius, fillPaint)
        }
        if (b == B_ENTER) {
            strokePaint.color = AMBER
            strokePaint.strokeWidth = border * 1.5f
            canvas.drawRoundRect(r, radius, radius, strokePaint)
            strokePaint.strokeWidth = border
        }
        val ink = when {
            active -> INK
            b == B_ENTER -> AMBER
            header && !pressed -> MUTED
            else -> TEXT
        }
        if (btnShowIcon[b]) {
            iconPaint.color = ink
            canvas.drawPath(btnIcon[b], iconPaint)
        }
        val label = keyLabel(b)
        if (btnShowLabel[b] && label != null) {
            labelPaint.color = ink
            labelPaint.textSize = btnTextSize[b]
            canvas.drawText(label, btnLabelX[b], r.centerY() + btnTextSize[b] * labelRatio, labelPaint)
        }
        val hint = KEY_HINTS[b]
        if (btnShowBadge[b] && hint != null) drawBadge(canvas, btnBadge[b], hint, active)
    }

    /** Controller badge in the hint-bar style: ink on muted grey, inverted on an amber (active) key. */
    private fun drawBadge(canvas: Canvas, r: RectF, text: String, inverted: Boolean) {
        fillPaint.color = if (inverted) INK else MUTED
        canvas.drawRoundRect(r, badgeCorner, badgeCorner, fillPaint)
        badgePaint.color = if (inverted) AMBER else INK
        canvas.drawText(text, r.centerX(), r.centerY() + badgePaint.textSize * badgeRatio, badgePaint)
    }

    private fun drawFitted(canvas: Canvas, text: String, cx: Float, cy: Float, maxW: Float, pref: Float, paint: Paint, ratio: Float) {
        paint.textSize = pref
        val w = paint.measureText(text)
        val size = if (w > maxW && w > 0f) pref * maxW / w else pref
        paint.textSize = size
        canvas.drawText(text, cx, cy + size * ratio, paint)
    }

    private fun baselineRatio(paint: Paint): Float {
        paint.textSize = 100f
        paint.getFontMetrics(fontMetrics)
        return -(fontMetrics.ascent + fontMetrics.descent) / 200f
    }

    // endregion

    // region Touch

    /** Accessibility clicks arrive through [KeyProvider]; raw touches here handle multi-touch wheels and key repeat. */
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = event.actionIndex
                pointerDown(event.getPointerId(i), event.getX(i), event.getY(i))
            }
            MotionEvent.ACTION_MOVE -> for (i in 0 until event.pointerCount) pointerMove(event.getPointerId(i), event.getX(i), event.getY(i))
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val i = event.actionIndex
                pointerUp(event.getPointerId(i), event.getX(i), event.getY(i))
            }
            MotionEvent.ACTION_CANCEL -> cancelPointers()
        }
        return true
    }

    private fun pointerDown(id: Int, x: Float, y: Float) {
        if (id < 0 || id >= MAX_POINTERS || wheelR <= 0f) return
        val key = keyAt(x, y)
        val dl = hypot(x - leftCx, y - leftCy)
        val dr = hypot(x - rightCx, y - rightCy)
        when {
            key != T_NONE -> {
                pointerKind[id] = P_KEY
                pointerTarget[id] = key
                if (key == T_BACKSPACE) {
                    send(KeyboardAction.BACKSPACE)
                    removeCallbacks(repeatDelete)
                    postDelayed(repeatDelete, REPEAT_DELAY_MS)
                }
            }
            dl <= wheelR + gap / 2 -> {
                pointerKind[id] = P_LEFT
                pointerTarget[id] = T_NONE
                trackGroup(x, y, dl)
            }
            dr <= rightCenterR -> {
                pointerKind[id] = P_KEY
                pointerTarget[id] = T_COMMIT
            }
            dr <= wheelR + gap / 2 -> {
                val slot = sector(x - rightCx, y - rightCy, RadialKeyboardModel.RIGHT_SECTORS)
                pointerKind[id] = P_RIGHT
                pointerTarget[id] = slot
                if (model.selectRightSector(slot)) refresh()
            }
            else -> {
                pointerKind[id] = P_NONE
                pointerTarget[id] = T_NONE
            }
        }
        updatePressed()
    }

    private fun pointerMove(id: Int, x: Float, y: Float) {
        if (id < 0 || id >= MAX_POINTERS) return
        when (pointerKind[id]) {
            P_LEFT -> trackGroup(x, y, hypot(x - leftCx, y - leftCy))
            P_RIGHT -> {
                val d = hypot(x - rightCx, y - rightCy)
                if (d > wheelR + gap * 1.5f) {
                    // Dragged well off the wheel: lifting will not type.
                    pointerKind[id] = P_NONE
                    pointerTarget[id] = T_NONE
                } else if (d > rightCenterR * 0.6f) {
                    val slot = sector(x - rightCx, y - rightCy, RadialKeyboardModel.RIGHT_SECTORS)
                    if (slot != pointerTarget[id]) {
                        pointerTarget[id] = slot
                        if (model.selectRightSector(slot)) refresh()
                    }
                }
            }
            P_KEY -> {
                val t = pointerTarget[id]
                if (t != T_NONE && !inside(t, x, y)) {
                    if (t == T_BACKSPACE) removeCallbacks(repeatDelete)
                    pointerTarget[id] = T_NONE
                    updatePressed()
                }
            }
        }
    }

    private fun pointerUp(id: Int, x: Float, y: Float) {
        if (id < 0 || id >= MAX_POINTERS) return
        val kind = pointerKind[id]
        val t = pointerTarget[id]
        pointerKind[id] = P_NONE
        pointerTarget[id] = T_NONE
        updatePressed()
        when {
            t == T_NONE -> Unit
            kind == P_RIGHT && hypot(x - rightCx, y - rightCy) <= wheelR + gap * 1.5f -> {
                if (model.selectRightSector(t)) refresh()
                send(KeyboardAction.COMMIT)
            }
            kind == P_KEY && t == T_BACKSPACE -> if (!held(T_BACKSPACE)) removeCallbacks(repeatDelete)
            kind == P_KEY && inside(t, x, y) -> activate(t)
        }
    }

    private fun cancelPointers() {
        pointerKind.fill(P_NONE)
        pointerTarget.fill(T_NONE)
        removeCallbacks(repeatDelete)
        updatePressed()
    }

    private fun trackGroup(x: Float, y: Float, distance: Float) {
        if (distance < leftCenterR * 0.6f) return
        if (model.selectGroup(sector(x - leftCx, y - leftCy, GROUPS))) {
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            refresh()
        }
    }

    /** Sector of a direction, 0 = up, clockwise, centred on each compass point. */
    private fun sector(dx: Float, dy: Float, count: Int): Int {
        var a = atan2(dx, -dy)
        if (a < 0f) a += TAU
        val step = TAU / count
        return ((a + step / 2) / step).toInt() % count
    }

    private fun keyAt(x: Float, y: Float): Int {
        for (b in 0 until B_COUNT) {
            if (btnRect[b].width() <= 0f) continue
            if (b < B_ENTER) {
                val r = btnRect[b]
                if (hypot(x - r.centerX(), y - r.centerY()) <= rightPipR + gap / 4) return T_SHIFT + b
            } else if (btnHit[b].contains(x, y)) {
                return T_SHIFT + b
            }
        }
        return T_NONE
    }

    private fun inside(t: Int, x: Float, y: Float): Boolean {
        if (t == T_COMMIT) return hypot(x - rightCx, y - rightCy) <= rightCenterR + gap
        val r = btnHit[t - T_SHIFT]
        if (t - T_SHIFT < B_ENTER) return hypot(x - r.centerX(), y - r.centerY()) <= rightPipR + gap / 4
        return x >= r.left - gap && x <= r.right + gap && y >= r.top - gap && y <= r.bottom + gap
    }

    private fun held(t: Int) = pressedMask and (1 shl t) != 0

    private fun updatePressed() {
        var mask = 0
        for (i in 0 until MAX_POINTERS) if (pointerKind[i] == P_KEY && pointerTarget[i] != T_NONE) mask = mask or (1 shl pointerTarget[i])
        if (mask != pressedMask) {
            pressedMask = mask
            invalidate()
        }
    }

    private fun activate(t: Int) {
        when {
            t in 0 until GROUPS -> if (model.selectGroup(t)) refresh()
            t in T_CHAR0 until T_COMMIT -> {
                if (model.selectCharacter(t - T_CHAR0)) refresh()
                send(KeyboardAction.COMMIT)
            }
            t == T_COMMIT -> if (commitLabel().isNotEmpty()) send(KeyboardAction.COMMIT)
            t in T_SHIFT until T_COUNT -> send(KEY_ACTIONS[t - T_SHIFT])
        }
    }

    private fun send(action: KeyboardAction) {
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        onAction(action)
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility != VISIBLE) cancelPointers()
    }

    override fun onDetachedFromWindow() {
        cancelPointers()
        hoverTarget = T_NONE
        super.onDetachedFromWindow()
    }

    // endregion

    // region Accessibility

    override fun getAccessibilityNodeProvider(): AccessibilityNodeProvider = provider

    override fun dispatchHoverEvent(event: MotionEvent): Boolean {
        val manager = a11y
        if (manager == null || !manager.isEnabled || !manager.isTouchExplorationEnabled) return super.dispatchHoverEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> setHover(targetAt(event.x, event.y))
            MotionEvent.ACTION_HOVER_EXIT -> setHover(T_NONE)
        }
        return true
    }

    private fun setHover(t: Int) {
        if (t == hoverTarget) return
        val old = hoverTarget
        hoverTarget = t
        if (t != T_NONE) sendVirtual(t, AccessibilityEvent.TYPE_VIEW_HOVER_ENTER)
        if (old != T_NONE) sendVirtual(old, AccessibilityEvent.TYPE_VIEW_HOVER_EXIT)
    }

    private fun targetAt(x: Float, y: Float): Int {
        val key = keyAt(x, y)
        if (key != T_NONE) return key
        for (g in 0 until GROUPS) if (hypot(x - groupX[g], y - groupY[g]) <= leftPipR * 1.15f) return g
        for (i in 0 until SLOTS) {
            if (targetVisible(T_CHAR0 + i) && hypot(x - charX[i], y - charY[i]) <= rightPipR * 1.15f) return T_CHAR0 + i
        }
        if (targetVisible(T_COMMIT) && hypot(x - rightCx, y - rightCy) <= rightCenterR) return T_COMMIT
        return T_NONE
    }

    private fun targetVisible(t: Int): Boolean = when {
        wheelR <= 0f -> false
        t in 0 until GROUPS -> true
        t in T_CHAR0 until T_COMMIT -> model.characterLabels.getOrNull(t - T_CHAR0)?.isNotEmpty() == true
        t == T_COMMIT -> commitLabel().isNotEmpty()
        t in T_SHIFT until T_COUNT -> btnRect[t - T_SHIFT].width() > 0f
        else -> false
    }

    private fun targetBounds(t: Int, out: RectF) {
        when {
            t in 0 until GROUPS -> out.set(groupX[t] - leftPipR, groupY[t] - leftPipR, groupX[t] + leftPipR, groupY[t] + leftPipR)
            t in T_CHAR0 until T_COMMIT -> {
                val i = t - T_CHAR0
                out.set(charX[i] - rightPipR, charY[i] - rightPipR, charX[i] + rightPipR, charY[i] + rightPipR)
            }
            t == T_COMMIT -> out.set(rightCx - rightCenterR, rightCy - rightCenterR, rightCx + rightCenterR, rightCy + rightCenterR)
            else -> out.set(btnRect[t - T_SHIFT])
        }
    }

    private fun describe(t: Int): String = when {
        t in 0 until GROUPS -> groupDescription(t)
        t in T_CHAR0 until T_COMMIT -> {
            val i = t - T_CHAR0
            "${spoken(model.characterLabels.getOrNull(i).orEmpty())}, ${DIRECTIONS_4[i]}"
        }
        t == T_COMMIT -> if (model.selectedAction != null) commitLabel() else "Type ${spoken(model.selectedCharacter)}"
        t == T_ENTER -> enterLabel
        t in T_SHIFT until T_COUNT -> KEY_NAMES[t - T_SHIFT]
        else -> ""
    }

    private fun groupDescription(g: Int): String =
        model.groupLabels.getOrNull(g).orEmpty().map { spokenChar(it) }.joinToString(", ", prefix = "${DIRECTIONS_8[g]}: ")

    private fun layerName(): String = when {
        model.shifted && model.symbols -> "More symbols"
        model.symbols -> "Numbers and symbols"
        model.shifted -> "Capital letters"
        else -> "Lowercase letters"
    }

    private fun sendVirtual(t: Int, type: Int) {
        if (a11y?.isEnabled != true) return
        val parent = parent ?: return
        val event = AccessibilityEvent(type)
        event.setSource(this, t)
        event.packageName = context.packageName
        event.className = BUTTON_CLASS
        event.contentDescription = describe(t)
        event.isEnabled = true
        parent.requestSendAccessibilityEvent(this, event)
    }

    private fun sendContentChanged() {
        val parent = parent ?: return
        val event = AccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        event.contentChangeTypes = AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE
        event.setSource(this)
        event.packageName = context.packageName
        parent.requestSendAccessibilityEvent(this, event)
    }

    private inner class KeyProvider : AccessibilityNodeProvider() {
        override fun createAccessibilityNodeInfo(virtualViewId: Int): AccessibilityNodeInfo? {
            val host = this@RadialKeyboardView
            if (virtualViewId == AccessibilityNodeProvider.HOST_VIEW_ID) {
                val info = AccessibilityNodeInfo(host)
                host.onInitializeAccessibilityNodeInfo(info)
                for (t in 0 until T_COUNT) if (targetVisible(t)) info.addChild(host, t)
                return info
            }
            if (!targetVisible(virtualViewId)) return null
            val info = AccessibilityNodeInfo(host, virtualViewId)
            info.setParent(host)
            info.packageName = context.packageName
            info.className = BUTTON_CLASS
            info.contentDescription = describe(virtualViewId)
            info.isEnabled = true
            info.isVisibleToUser = true
            info.isClickable = true
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)
            if (a11yFocus == virtualViewId) {
                info.isAccessibilityFocused = true
                info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLEAR_ACCESSIBILITY_FOCUS)
            } else {
                info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_ACCESSIBILITY_FOCUS)
            }
            if (virtualViewId == T_SHIFT || virtualViewId == T_SYMBOLS) {
                val on = if (virtualViewId == T_SHIFT) model.shifted else model.symbols
                info.isCheckable = true
                info.isChecked = on
                info.stateDescription = if (on) "On" else "Off"
            }
            val selectedChar = model.selectedIndex
            if (virtualViewId == model.selectedGroup || (selectedChar != RadialKeyboardModel.NONE && virtualViewId == T_CHAR0 + selectedChar)) {
                info.isSelected = true
            }
            targetBounds(virtualViewId, boundsF)
            host.getLocationOnScreen(screenLoc)
            bounds.set(
                boundsF.left.toInt() + screenLoc[0],
                boundsF.top.toInt() + screenLoc[1],
                boundsF.right.toInt() + screenLoc[0],
                boundsF.bottom.toInt() + screenLoc[1]
            )
            info.setBoundsInScreen(bounds)
            return info
        }

        override fun performAction(virtualViewId: Int, action: Int, arguments: Bundle?): Boolean {
            val host = this@RadialKeyboardView
            if (virtualViewId == AccessibilityNodeProvider.HOST_VIEW_ID) return host.performAccessibilityAction(action, arguments)
            if (!targetVisible(virtualViewId)) return false
            return when (action) {
                AccessibilityNodeInfo.ACTION_CLICK -> {
                    sendVirtual(virtualViewId, AccessibilityEvent.TYPE_VIEW_CLICKED)
                    activate(virtualViewId)
                    true
                }
                AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS -> {
                    if (a11yFocus != virtualViewId) {
                        val old = a11yFocus
                        a11yFocus = virtualViewId
                        if (old != T_NONE) sendVirtual(old, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED)
                        sendVirtual(virtualViewId, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED)
                    }
                    true
                }
                AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS -> {
                    if (a11yFocus == virtualViewId) {
                        a11yFocus = T_NONE
                        sendVirtual(virtualViewId, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED)
                    }
                    true
                }
                else -> false
            }
        }
    }

    // endregion

    private fun selectedActionButton(): Int = when (model.selectedAction) {
        KeyboardAction.BACKSPACE -> B_BACKSPACE
        KeyboardAction.SPACE -> B_SPACE
        KeyboardAction.TOGGLE_SYMBOLS -> B_SYMBOLS
        KeyboardAction.TOGGLE_SHIFT -> B_SHIFT
        else -> T_NONE
    }

    private fun commitLabel(): String = when (val b = selectedActionButton()) {
        T_NONE -> model.selectedCharacter
        B_BACKSPACE -> "Delete"
        else -> keyLabel(b).orEmpty()
    }

    private fun keyLabel(b: Int): String? = when (b) {
        B_SHIFT -> "Shift"
        B_SYMBOLS -> "?123"
        B_SPACE -> "Space"
        B_ENTER -> enterLabel
        B_HIDE -> "Hide"
        B_NEXT -> "Switch keyboard"
        else -> null
    }

    private fun hasIcon(b: Int) = b == B_BACKSPACE || b == B_HIDE || b == B_NEXT

    private companion object {
        const val DEFAULT_ENTER = "Enter"
        const val GROUPS = RadialKeyboardModel.GROUPS
        const val SLOTS = RadialKeyboardModel.SLOTS
        const val TAU = 6.2831855f
        const val ARC_SWEEP = 40f
        const val LANDSCAPE_SHARE = 0.5f
        const val PORTRAIT_SHARE = 0.42f
        const val REPEAT_DELAY_MS = 400L
        const val REPEAT_INTERVAL_MS = 60L
        const val MAX_POINTERS = 10
        const val BUTTON_CLASS = "android.widget.Button"
        const val BADGE_LEFT_STICK = "LS"
        const val BADGE_COMMIT = "A"

        const val B_SHIFT = 0
        const val B_SYMBOLS = 1
        const val B_BACKSPACE = 2
        const val B_SPACE = 3
        const val B_ENTER = 4
        const val B_HIDE = 5
        const val B_NEXT = 6
        const val B_COUNT = 7

        // Targets: 0..7 groups, then characters, the commit disc and the keys (T_SHIFT + B_*). Fits one Int bit mask.
        const val T_NONE = -1
        const val T_CHAR0 = GROUPS
        const val T_COMMIT = T_CHAR0 + SLOTS
        const val T_SHIFT = T_COMMIT + 1
        const val T_SYMBOLS = T_SHIFT + B_SYMBOLS
        const val T_BACKSPACE = T_SHIFT + B_BACKSPACE
        const val T_ENTER = T_SHIFT + B_ENTER
        const val T_COUNT = T_SHIFT + B_COUNT

        const val P_NONE = 0
        const val P_LEFT = 1
        const val P_RIGHT = 2
        const val P_KEY = 3

        const val INK = 0xFF101418.toInt()
        const val DEEP = 0xE00B0D10.toInt()
        const val SLATE = 0xE01B2129.toInt()
        const val SLATE_HI = 0xEE29313B.toInt()
        const val LINE = 0xFF2A323C.toInt()
        const val TEXT = 0xFFE6E8EB.toInt()
        const val PIP_TEXT = 0xFFC9CFD6.toInt()
        const val MUTED = 0xFF9AA3AD.toInt()
        const val AMBER = 0xFFF2B134.toInt()
        const val AMBER_DOWN = 0xFFD39621.toInt()
        val DIAGONAL_BUTTONS = intArrayOf(B_BACKSPACE, B_SPACE, B_SYMBOLS, B_SHIFT)

        val KEY_ACTIONS = arrayOf(
            KeyboardAction.TOGGLE_SHIFT,
            KeyboardAction.TOGGLE_SYMBOLS,
            KeyboardAction.BACKSPACE,
            KeyboardAction.SPACE,
            KeyboardAction.ENTER,
            KeyboardAction.HIDE,
            KeyboardAction.NEXT_IME
        )
        val KEY_HINTS = arrayOf("L2", "R2", "X", "Y", "Start", "B", null)
        val KEY_NAMES = arrayOf("Shift", "Symbols", "Delete", "Space", DEFAULT_ENTER, "Hide keyboard", "Switch keyboard")
        val DIRECTIONS_8 = arrayOf("Up", "Up right", "Right", "Down right", "Down", "Down left", "Left", "Up left")
        val DIRECTIONS_4 = arrayOf("up", "right", "down", "left")

        fun spoken(text: String): String = if (text.length == 1) spokenChar(text[0]) else text

        fun spokenChar(c: Char): String = when (c) {
            in 'A'..'Z' -> "capital $c"
            ' ' -> "space"
            '!' -> "exclamation mark"
            '"' -> "quotation mark"
            '#' -> "number sign"
            '$' -> "dollar sign"
            '%' -> "percent"
            '&' -> "ampersand"
            '\'' -> "apostrophe"
            '(' -> "left parenthesis"
            ')' -> "right parenthesis"
            '*' -> "asterisk"
            '+' -> "plus"
            ',' -> "comma"
            '-' -> "hyphen"
            '.' -> "period"
            '/' -> "slash"
            ':' -> "colon"
            ';' -> "semicolon"
            '<' -> "less than"
            '=' -> "equals"
            '>' -> "greater than"
            '?' -> "question mark"
            '@' -> "at sign"
            '[' -> "left bracket"
            '\\' -> "backslash"
            ']' -> "right bracket"
            '^' -> "caret"
            '_' -> "underscore"
            '`' -> "grave accent"
            '{' -> "left brace"
            '|' -> "vertical bar"
            '}' -> "right brace"
            '~' -> "tilde"
            else -> c.toString()
        }
    }
}
