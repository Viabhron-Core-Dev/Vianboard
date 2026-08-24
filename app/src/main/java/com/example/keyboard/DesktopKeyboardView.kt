package com.example.keyboard

import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import com.example.keyboard.desktop.DesktopShortcutsManager
import com.example.keyboard.longpress.LongPressSettingsManager

interface DesktopKeyListener {
    fun onDesktopKey(code: String)
}

data class DesktopKey(
    val code: String,
    val label: String,
    val sublabel: String = "",
    val longPressCode: String = "",
    val longPressLabel: String = "",
    val isFunctional: Boolean = false,
    val weight: Float = 1.0f
) {
    var rect: RectF = RectF()
    var isPressed: Boolean = false
}

class DesktopKeyboardView(context: Context) : View(context) {
    var listener: DesktopKeyListener? = null
    var isSelectMode = false

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val subTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val lpTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)

    private val keys = mutableListOf<List<DesktopKey>>()
    private var pressedKey: DesktopKey? = null
    private val handler = Handler(Looper.getMainLooper())
    private val longPressRunnable = Runnable { triggerLongPress() }
    private var isLongPressTriggered = false

    init {
        textPaint.color = Color.WHITE
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)

        subTextPaint.color = Color.parseColor("#9E9E9E")
        subTextPaint.textAlign = Paint.Align.CENTER

        lpTextPaint.color = Color.parseColor("#757575")
        lpTextPaint.textAlign = Paint.Align.RIGHT

        setupKeys()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        reloadKeys()
    }

    fun reloadKeys() {
        setupKeys()
        if (width > 0 && height > 0) {
            calculateKeyRects(width, height)
        }
        invalidate()
    }

    private fun setupKeys() {
        keys.clear()
        val customShortcuts = DesktopShortcutsManager.getEnabledShortcuts(context)

        if (customShortcuts.isEmpty()) {
            // Default Layout: Pure Fat Directional Pad + Core Essentials
            // Row 0: Home, Up Arrow, End
            keys.add(
                listOf(
                    DesktopKey("DSK_HOME", "HOME", "Start", isFunctional = true, weight = 1.0f),
                    DesktopKey("DSK_UP", "▲", "Up", "DSK_PGUP", "PgUp", isFunctional = true, weight = 1.5f),
                    DesktopKey("DSK_END", "END", "End", isFunctional = true, weight = 1.0f)
                )
            )

            // Row 1: Left Arrow, Down Arrow, Right Arrow
            keys.add(
                listOf(
                    DesktopKey("DSK_LEFT", "◀", "Left", isFunctional = true, weight = 1.2f),
                    DesktopKey("DSK_DOWN", "▼", "Down", "DSK_PGDN", "PgDn", isFunctional = true, weight = 1.2f),
                    DesktopKey("DSK_RIGHT", "▶", "Right", isFunctional = true, weight = 1.2f)
                )
            )

            // Row 2: Bottom Bar (ABC, Select, Space, Backspace, Enter)
            keys.add(
                listOf(
                    DesktopKey("MODE_ALPHABET", "ABC", "", isFunctional = true, weight = 1.2f),
                    DesktopKey("DSK_SEL", "SEL", "Select", isFunctional = true, weight = 1.0f),
                    DesktopKey("SPACE", "Space", "", isFunctional = false, weight = 2.0f),
                    DesktopKey("DSK_BKSP", "⌫", "Del", "DSK_DEL", "FwdDel", isFunctional = true, weight = 1.2f),
                    DesktopKey("DSK_ENTER", "↵", "Enter", isFunctional = true, weight = 1.2f)
                )
            )
        } else {
            // Customized Layout with Fat Buttons
            // Convert custom shortcut IDs to DesktopKey objects
            val customKeys = customShortcuts.mapNotNull { id ->
                val item = DesktopShortcutsManager.getShortcutItem(id) ?: return@mapNotNull null
                DesktopKey(item.actionCode, item.label, item.sublabel, isFunctional = true, weight = 1.0f)
            }

            // Split custom keys across 2 rows (max 3 custom keys per row)
            val half = (customKeys.size + 1) / 2
            val topCustom = customKeys.take(half)
            val midCustom = customKeys.drop(half)

            // Row 0: Top Custom Keys + Up Arrow (+ optional Home)
            val row0 = mutableListOf<DesktopKey>()
            row0.addAll(topCustom)
            if (row0.isEmpty()) {
                row0.add(DesktopKey("DSK_HOME", "HOME", "Start", isFunctional = true, weight = 1.0f))
            }
            row0.add(DesktopKey("DSK_UP", "▲", "Up", "DSK_PGUP", "PgUp", isFunctional = true, weight = 1.4f))
            keys.add(row0)

            // Row 1: Mid Custom Keys + Left, Down, Right Arrows
            val row1 = mutableListOf<DesktopKey>()
            row1.addAll(midCustom)
            row1.add(DesktopKey("DSK_LEFT", "◀", "Left", isFunctional = true, weight = 1.0f))
            row1.add(DesktopKey("DSK_DOWN", "▼", "Down", "DSK_PGDN", "PgDn", isFunctional = true, weight = 1.0f))
            row1.add(DesktopKey("DSK_RIGHT", "▶", "Right", isFunctional = true, weight = 1.0f))
            keys.add(row1)

            // Row 2: Bottom Bar (ABC, SEL, Space, Del, Enter)
            keys.add(
                listOf(
                    DesktopKey("MODE_ALPHABET", "ABC", "", isFunctional = true, weight = 1.2f),
                    DesktopKey("DSK_SEL", "SEL", "Select", isFunctional = true, weight = 1.0f),
                    DesktopKey("SPACE", "Space", "", isFunctional = false, weight = 2.0f),
                    DesktopKey("DSK_BKSP", "⌫", "Del", "DSK_DEL", "FwdDel", isFunctional = true, weight = 1.2f),
                    DesktopKey("DSK_ENTER", "↵", "Enter", isFunctional = true, weight = 1.2f)
                )
            )
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        calculateKeyRects(w, h)
    }

    private fun calculateKeyRects(w: Int, h: Int) {
        if (keys.isEmpty()) return
        val rowCount = keys.size
        val rowHeight = h / rowCount.toFloat()
        val padding = 5f

        for (rowIndex in 0 until rowCount) {
            val row = keys[rowIndex]
            val totalWeight = row.sumOf { it.weight.toDouble() }.toFloat()
            var currentX = 0f

            for (key in row) {
                val keyWidth = (key.weight / totalWeight) * w
                key.rect.set(
                    currentX + padding,
                    rowIndex * rowHeight + padding,
                    currentX + keyWidth - padding,
                    (rowIndex + 1) * rowHeight - padding
                )
                currentX += keyWidth
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val density = resources.displayMetrics.density
        val normalBgColor = Color.parseColor("#383838")
        val functionalBgColor = Color.parseColor("#2A2A2A")
        val pressedBgColor = Color.parseColor("#5A5A5A")
        val selActiveColor = Color.parseColor("#1976D2")
        val cornerRadius = 14f * density

        for (row in keys) {
            for (key in row) {
                // Background
                bgPaint.color = when {
                    key.isPressed -> pressedBgColor
                    key.code == "DSK_SEL" && isSelectMode -> selActiveColor
                    key.isFunctional -> functionalBgColor
                    else -> normalBgColor
                }
                canvas.drawRoundRect(key.rect, cornerRadius, cornerRadius, bgPaint)

                // Main label
                textPaint.textSize = if (key.code.startsWith("DSK_") && (key.label == "▲" || key.label == "▼" || key.label == "◀" || key.label == "▶")) {
                    22f * density
                } else if (key.label.length > 4) {
                    13f * density
                } else {
                    16f * density
                }

                val hasSublabel = key.sublabel.isNotEmpty()
                val centerYOffset = if (hasSublabel) key.rect.height() * 0.38f else key.rect.height() * 0.5f
                val textY = key.rect.top + centerYOffset - ((textPaint.descent() + textPaint.ascent()) / 2)
                canvas.drawText(key.label, key.rect.centerX(), textY, textPaint)

                // Sublabel
                if (hasSublabel) {
                    subTextPaint.textSize = 10f * density
                    var sublabelToDraw = key.sublabel
                    val maxLabelWidth = key.rect.width() - 8f * density
                    if (subTextPaint.measureText(sublabelToDraw) > maxLabelWidth) {
                        sublabelToDraw = TextUtils.ellipsize(sublabelToDraw, subTextPaint, maxLabelWidth, TextUtils.TruncateAt.END).toString()
                    }
                    val subY = key.rect.top + key.rect.height() * 0.76f - ((subTextPaint.descent() + subTextPaint.ascent()) / 2)
                    canvas.drawText(sublabelToDraw, key.rect.centerX(), subY, subTextPaint)
                }

                // Long press indicator
                if (key.longPressLabel.isNotEmpty()) {
                    lpTextPaint.textSize = 9f * density
                    val lpX = key.rect.right - 6f * density
                    val lpY = key.rect.bottom - 6f * density
                    canvas.drawText(key.longPressLabel, lpX, lpY, lpTextPaint)
                }
            }
        }
    }

    fun toggleSelectMode() {
        isSelectMode = !isSelectMode
        invalidate()
    }

    fun disarmSelectMode() {
        if (isSelectMode) {
            isSelectMode = false
            invalidate()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                val key = findKey(x, y)
                if (key != null) {
                    pressedKey = key
                    key.isPressed = true
                    isLongPressTriggered = false
                    val delay = LongPressSettingsManager.getLongPressDelay(context).toLong()
                    handler.postDelayed(longPressRunnable, delay)
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(longPressRunnable)
                pressedKey?.let {
                    it.isPressed = false
                    if (!isLongPressTriggered) {
                        fireKey(it)
                    }
                }
                pressedKey = null
                invalidate()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPressRunnable)
                pressedKey?.isPressed = false
                pressedKey = null
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                pressedKey?.let {
                    if (!it.rect.contains(x, y)) {
                        handler.removeCallbacks(longPressRunnable)
                        it.isPressed = false
                        pressedKey = null
                        invalidate()
                    }
                }
            }
        }
        return super.onTouchEvent(event)
    }

    private fun findKey(x: Float, y: Float): DesktopKey? {
        for (row in keys) {
            for (key in row) {
                if (key.rect.contains(x, y)) {
                    return key
                }
            }
        }
        return null
    }

    private fun fireKey(key: DesktopKey) {
        if (key.code == "DSK_SEL") {
            toggleSelectMode()
            listener?.onDesktopKey(key.code)
            return
        }

        var codeToSend = key.code

        if (isSelectMode) {
            when (key.code) {
                "DSK_LEFT" -> codeToSend = "DSK_SEL_LEFT"
                "DSK_RIGHT" -> codeToSend = "DSK_SEL_RIGHT"
                "DSK_UP" -> codeToSend = "DSK_SEL_UP"
                "DSK_DOWN" -> codeToSend = "DSK_SEL_DOWN"
            }
        }

        listener?.onDesktopKey(codeToSend)

        if (key.code == "DSK_COPY" || key.code == "DSK_CUT" || key.code == "DSK_BKSP") {
            disarmSelectMode()
        }
    }

    private fun triggerLongPress() {
        val key = pressedKey ?: return
        isLongPressTriggered = true

        if (key.longPressCode.isNotEmpty()) {
            listener?.onDesktopKey(key.longPressCode)
        }

        key.isPressed = false
        pressedKey = null
        invalidate()
    }
}
