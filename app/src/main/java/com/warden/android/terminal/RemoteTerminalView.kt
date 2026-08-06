package com.warden.android.terminal

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.view.ActionMode
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import com.termux.terminal.KeyHandler
import com.termux.terminal.TerminalEmulator
import com.termux.view.TerminalRenderer
import java.nio.charset.StandardCharsets
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A minimal Android terminal view that renders a [RemoteTerminalSession]'s
 * emulator with Termux's [TerminalRenderer] and forwards user input as raw
 * bytes. It is deliberately *not* Termux's `TerminalView` — that one is welded
 * to a local subprocess `TerminalSession`. Here input goes straight to the
 * caller (the WS transport) and the screen updates from the server's echo, the
 * normal remote-terminal round-trip.
 *
 * Covers hardware + soft-keyboard input, Ctrl/Alt modifiers, special keys (via
 * [KeyHandler]), resize on layout, touch scrollback — the last either pans our
 * local transcript or, when the app has enabled mouse tracking, forwards swipes
 * as wheel events — and long-press **text selection** with a floating Copy
 * toolbar (drives the emulator's own selection extraction, so it reuses the
 * daemon's scrollback, no re-implementation of the buffer).
 */
@SuppressLint("ViewConstructor")
class RemoteTerminalView(
    context: Context,
    textSizeSp: Float = DEFAULT_TEXT_SIZE_SP,
) : View(context) {

    /** Reports the measured grid size whenever it changes (including first layout). */
    var onGridSizeChanged: ((cols: Int, rows: Int) -> Unit)? = null

    /** Emits raw bytes to send to the PTY (keystrokes). */
    var onInput: ((ByteArray) -> Unit)? = null

    private val renderer: TerminalRenderer
    private var session: RemoteTerminalSession? = null

    /** 0 = pinned to the live bottom; negative = scrolled up into scrollback. */
    private var topRow = 0
    private var scrollRemainder = 0f

    // ---- text selection ----
    // Endpoints are (column, absolute-row) in the emulator's own coordinate
    // space — the same space topRow lives in and that TerminalRenderer.render /
    // TerminalEmulator.getSelectedText expect: column 0-based, row absolute
    // (visible rows run [topRow, topRow+rows); negative reaches into scrollback).
    // Stored raw (anchor = endpoint 1, may sit after endpoint 2); everything that
    // consumes the pair normalises via [orderedSelection] first.
    private var selecting = false
    private var selCol1 = 0
    private var selRow1 = 0
    private var selCol2 = 0
    private var selRow2 = 0
    private var draggingEnd = 0 // 0 none, 1 anchor, 2 free end
    private var actionMode: ActionMode? = null
    private val handlePaint = Paint().apply {
        isAntiAlias = true
        color = SELECTION_HANDLE_COLOR
    }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        setBackgroundColor(Color.BLACK)
        val textSizePx = (textSizeSp * resources.displayMetrics.scaledDensity).roundToInt()
        renderer = TerminalRenderer(textSizePx, Typeface.MONOSPACE)
    }

    /** Bind the emulator session this view renders (created once the size is known). */
    fun setSession(session: RemoteTerminalSession) {
        this.session = session
        topRow = 0
        clearSelectionState()
        invalidate()
    }

    /** Snap back to live output and repaint (call when new bytes arrive). */
    fun followOutput() {
        topRow = 0
        invalidate()
    }

    /** Repaint without changing the scroll position (new server bytes arrived). */
    fun repaint() = invalidate()

    /** Send a special key (arrows, Tab, etc.) resolved against the emulator's modes. */
    fun sendKeyCode(keyCode: Int, ctrl: Boolean = false, alt: Boolean = false) {
        val s = session ?: return
        var mod = 0
        if (ctrl) mod = mod or KeyHandler.KEYMOD_CTRL
        if (alt) mod = mod or KeyHandler.KEYMOD_ALT
        val code = KeyHandler.getCode(
            keyCode, mod,
            s.emulator.isCursorKeysApplicationMode,
            s.emulator.isKeypadApplicationMode,
        )
        if (code != null) send(code.toByteArray(StandardCharsets.UTF_8))
    }

    /** Send raw bytes to the PTY (used by the on-screen key bar). */
    fun sendBytes(bytes: ByteArray) = send(bytes)

    fun showKeyboard() {
        requestFocus()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }

    // ---- sizing ----

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        reportGridSize(w, h)
    }

    private fun reportGridSize(w: Int, h: Int) {
        if (w == 0 || h == 0) return
        val cols = max(MIN_DIM, (w / renderer.fontWidth).toInt())
        val rows = max(MIN_DIM, h / renderer.fontLineSpacing)
        onGridSizeChanged?.invoke(cols, rows)
    }

    // ---- rendering ----

    override fun onDraw(canvas: Canvas) {
        val s = session ?: return
        clampTopRow(s)
        if (selecting) {
            val b = orderedSelection()
            // render(..., topRow, selectionY1, selectionY2, selectionX1, selectionX2)
            // — note the Y pair precedes the X pair (Termux's signature order).
            renderer.render(s.emulator, canvas, topRow, b[1], b[3], b[0], b[2])
            drawSelectionHandles(canvas, b)
        } else {
            // No active selection → pass the "none" sentinels for all four bounds.
            renderer.render(s.emulator, canvas, topRow, -1, -1, -1, -1)
        }
    }

    private fun clampTopRow(s: RemoteTerminalSession) {
        val history = s.emulator.screen.activeTranscriptRows
        if (topRow < -history) topRow = -history
        if (topRow > 0) topRow = 0
    }

    /** Two round handles at the selection's leading/trailing cell corners. */
    private fun drawSelectionHandles(canvas: Canvas, b: IntArray) {
        val r = renderer.fontLineSpacing * SELECTION_HANDLE_RADIUS_FRACTION
        val startX = b[0] * renderer.fontWidth
        val startY = ((b[1] - topRow) + 1) * renderer.fontLineSpacing.toFloat()
        val endX = (b[2] + 1) * renderer.fontWidth
        val endY = ((b[3] - topRow) + 1) * renderer.fontLineSpacing.toFloat()
        canvas.drawCircle(startX, startY, r, handlePaint)
        canvas.drawCircle(endX, endY, r, handlePaint)
    }

    // ---- touch: scrollback + selection ----

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                showKeyboard()
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                val s = session ?: return
                selectWordAt(s, colAt(s, e.x), rowAt(s, e.y))
                selecting = true
                // Long-press then keep the finger down to drag the free end out —
                // that gesture's later MOVE/UP events land in onSelectionTouch
                // (onTouchEvent routes there once `selecting` is true).
                draggingEnd = 2
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                startSelectionActionMode()
                invalidate()
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float,
            ): Boolean {
                val s = session ?: return true
                scrollRemainder += distanceY
                val lineHeight = renderer.fontLineSpacing
                val rows = (scrollRemainder / lineHeight).toInt()
                if (rows == 0) return true
                scrollRemainder -= rows * lineHeight
                // GestureDetector's distanceY = oldY − newY, so dragging the finger
                // *down* the screen is negative → reveal older content (scroll up),
                // matching natural touch scrolling.
                val scrollUp = rows < 0
                if (s.emulator.isMouseTrackingActive) {
                    // The running app has requested mouse tracking — tmux with
                    // `mouse on`, or a full-screen TUI (less/vim/Claude). Forward each
                    // notch as a wheel event so it scrolls *its own* view; alt-screen
                    // apps keep no local scrollback for us to pan. Gated on the actual
                    // emulator mode, so it's a no-op (falls through to local scroll)
                    // whenever mouse tracking is off — no protocol assumptions.
                    val button = if (scrollUp) {
                        TerminalEmulator.MOUSE_WHEELUP_BUTTON
                    } else {
                        TerminalEmulator.MOUSE_WHEELDOWN_BUTTON
                    }
                    val col = (e2.x / renderer.fontWidth).toInt() + 1
                    val row = (e2.y / renderer.fontLineSpacing).toInt() + 1
                    repeat(abs(rows)) { s.emulator.sendMouseEvent(button, col, row, true) }
                } else {
                    // No mouse tracking: pan our own scrollback transcript.
                    topRow += rows
                    invalidate()
                }
                return true
            }
        },
    )

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isFocused) requestFocus()
        // While a selection is active, touches adjust/dismiss it rather than
        // scrolling or opening the keyboard. Long-press to *start* selection is
        // still detected by the gesture detector on the non-selecting path.
        if (selecting) return onSelectionTouch(event)
        return gestureDetector.onTouchEvent(event)
    }

    private fun onSelectionTouch(event: MotionEvent): Boolean {
        val s = session ?: return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // A fresh tap: grab the nearer handle, or dismiss if it landed away
                // from the selection.
                val end = nearestEndpoint(colAt(s, event.x), rowAt(s, event.y))
                if (end == 0) {
                    exitSelection()
                    return true
                }
                draggingEnd = end
            }
            MotionEvent.ACTION_MOVE -> {
                if (draggingEnd != 0) {
                    moveEndpoint(draggingEnd, colAt(s, event.x), rowAt(s, event.y))
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                draggingEnd = 0
                actionMode?.invalidateContentRect() // reposition toolbar over the new bounds
            }
        }
        return true
    }

    // ---- selection geometry ----

    /** Pixel x → 0-based column, clamped to the grid. */
    private fun colAt(s: RemoteTerminalSession, x: Float): Int =
        (x / renderer.fontWidth).toInt().coerceIn(0, s.emulator.mColumns - 1)

    /** Pixel y → absolute row (visible cell row + [topRow]). */
    private fun rowAt(s: RemoteTerminalSession, y: Float): Int {
        val cell = (y / renderer.fontLineSpacing).toInt().coerceIn(0, s.emulator.mRows - 1)
        return cell + topRow
    }

    /** The character in one cell, via the emulator's own extractor (' ' if blank). */
    private fun charAt(s: RemoteTerminalSession, col: Int, row: Int): Char {
        val t = s.emulator.getSelectedText(col, row, col, row)
        return if (t.isNotEmpty()) t[0] else ' '
    }

    /** Seed the selection with the whitespace-delimited run under the touch point. */
    private fun selectWordAt(s: RemoteTerminalSession, col: Int, row: Int) {
        selRow1 = row
        selRow2 = row
        if (charAt(s, col, row).isWhitespace()) {
            selCol1 = col
            selCol2 = col
            return
        }
        var a = col
        var b = col
        while (a > 0 && !charAt(s, a - 1, row).isWhitespace()) a--
        while (b < s.emulator.mColumns - 1 && !charAt(s, b + 1, row).isWhitespace()) b++
        selCol1 = a
        selCol2 = b
    }

    /** [x1, y1, x2, y2] with the start in reading order (top-left) before the end. */
    private fun orderedSelection(): IntArray =
        if (selRow1 < selRow2 || (selRow1 == selRow2 && selCol1 <= selCol2)) {
            intArrayOf(selCol1, selRow1, selCol2, selRow2)
        } else {
            intArrayOf(selCol2, selRow2, selCol1, selRow1)
        }

    /** Which raw endpoint (1 anchor / 2 free / 0 none) the touch is grabbing. */
    private fun nearestEndpoint(col: Int, row: Int): Int {
        val near1 = abs(row - selRow1) <= 1 && abs(col - selCol1) <= HANDLE_GRAB_COLS
        val near2 = abs(row - selRow2) <= 1 && abs(col - selCol2) <= HANDLE_GRAB_COLS
        val d1 = abs(row - selRow1) * 1000 + abs(col - selCol1)
        val d2 = abs(row - selRow2) * 1000 + abs(col - selCol2)
        return when {
            near1 && (!near2 || d1 <= d2) -> 1
            near2 -> 2
            else -> 0
        }
    }

    private fun moveEndpoint(which: Int, col: Int, row: Int) {
        if (which == 1) {
            selCol1 = col
            selRow1 = row
        } else {
            selCol2 = col
            selRow2 = row
        }
    }

    // ---- selection lifecycle + Copy toolbar ----

    private fun startSelectionActionMode() {
        actionMode?.finish()
        actionMode = startActionMode(
            object : ActionMode.Callback2() {
                override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                    menu.add(Menu.NONE, MENU_COPY, 0, android.R.string.copy)
                    menu.add(Menu.NONE, MENU_SELECT_ALL, 1, android.R.string.selectAll)
                    return true
                }

                override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = false

                override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean =
                    when (item.itemId) {
                        MENU_COPY -> {
                            copySelection()
                            mode.finish()
                            true
                        }
                        MENU_SELECT_ALL -> {
                            selectAll()
                            invalidate()
                            mode.invalidateContentRect()
                            true
                        }
                        else -> false
                    }

                override fun onDestroyActionMode(mode: ActionMode) {
                    actionMode = null
                    clearSelectionState()
                }

                override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
                    selectionPixelRect(outRect)
                }
            },
            ActionMode.TYPE_FLOATING,
        )
    }

    private fun copySelection() {
        val s = session ?: return
        val b = orderedSelection()
        val text = s.emulator.getSelectedText(b[0], b[1], b[2], b[3])
        if (text.isNotEmpty()) {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("terminal", text))
            Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
        }
        clearSelectionState()
    }

    private fun selectAll() {
        val s = session ?: return
        val history = s.emulator.screen.activeTranscriptRows
        selCol1 = 0
        selRow1 = -history
        selCol2 = s.emulator.mColumns - 1
        selRow2 = s.emulator.mRows - 1
    }

    /** The selection's bounding box in view pixels — anchors the floating toolbar. */
    private fun selectionPixelRect(outRect: Rect) {
        val b = orderedSelection()
        val left = (b[0] * renderer.fontWidth).toInt()
        val top = ((b[1] - topRow) * renderer.fontLineSpacing).coerceAtLeast(0)
        val right = ((b[2] + 1) * renderer.fontWidth).toInt()
        val bottom = ((b[3] - topRow) + 1) * renderer.fontLineSpacing
        outRect.set(left, top, right, bottom)
    }

    /** Reset selection state and repaint, without touching the action mode. */
    private fun clearSelectionState() {
        if (!selecting) return
        selecting = false
        draggingEnd = 0
        invalidate()
    }

    /** Fully leave selection: drop state and dismiss the toolbar (no recursion). */
    private fun exitSelection() {
        val am = actionMode
        actionMode = null
        clearSelectionState()
        am?.finish()
    }

    // ---- keyboard input ----

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        // TYPE_NULL makes most IMEs deliver hardware-style key events, which the
        // terminal wants; commitText covers IMEs that send text instead.
        outAttrs.inputType = EditorInfo.TYPE_NULL
        outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_FLAG_NO_FULLSCREEN
        return object : BaseInputConnection(this, true) {
            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.action == KeyEvent.ACTION_DOWN) onKeyDown(event.keyCode, event)
                return true
            }

            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                text?.toString()?.let(::inputText)
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                repeat(beforeLength) { send(byteArrayOf(0x7f)) } // DEL / backspace
                return true
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val s = session ?: return super.onKeyDown(keyCode, event)
        val ctrl = event.isCtrlPressed
        val alt = event.isAltPressed

        // Special keys (arrows, F-keys, Home/End, Enter, Tab, Backspace…) → escape codes.
        var keyMod = 0
        if (ctrl) keyMod = keyMod or KeyHandler.KEYMOD_CTRL
        if (alt) keyMod = keyMod or KeyHandler.KEYMOD_ALT
        if (event.isShiftPressed) keyMod = keyMod or KeyHandler.KEYMOD_SHIFT
        val code = KeyHandler.getCode(
            keyCode,
            keyMod,
            s.emulator.isCursorKeysApplicationMode,
            s.emulator.isKeypadApplicationMode,
        )
        if (code != null) {
            send(code.toByteArray(StandardCharsets.UTF_8))
            return true
        }

        // Printable character. Resolve the base code point ignoring Ctrl/Alt so we
        // can apply their terminal semantics ourselves.
        val metaWithoutMods = event.metaState and
            KeyEvent.META_CTRL_MASK.inv() and KeyEvent.META_ALT_MASK.inv()
        val codePoint = event.getUnicodeChar(metaWithoutMods)
        if (codePoint != 0) {
            inputCodePoint(codePoint, ctrl, alt)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun inputText(text: String) {
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            inputCodePoint(cp, ctrl = false, alt = false)
            i += Character.charCount(cp)
        }
    }

    /** Applies Ctrl/Alt terminal semantics and writes the code point as UTF-8. */
    private fun inputCodePoint(codePoint: Int, ctrl: Boolean, alt: Boolean) {
        var cp = codePoint
        if (ctrl) cp = controlChar(cp) ?: cp
        val bytes = utf8(cp)
        // Left-Alt sends ESC before the byte(s) (Meta), matching readline/emacs.
        send(if (alt) byteArrayOf(0x1b) + bytes else bytes)
    }

    /** Maps a code point to its control character, or null if it has none. */
    private fun controlChar(cp: Int): Int? = when (cp) {
        in 'a'.code..'z'.code -> cp - 'a'.code + 1
        in 'A'.code..'Z'.code -> cp - 'A'.code + 1
        ' '.code, '@'.code -> 0
        '['.code -> 27
        '\\'.code -> 28
        ']'.code -> 29
        '^'.code -> 30
        '_'.code -> 31
        '?'.code -> 127
        else -> null
    }

    private fun utf8(codePoint: Int): ByteArray =
        String(Character.toChars(codePoint)).toByteArray(StandardCharsets.UTF_8)

    private fun send(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        if (selecting) exitSelection() // typing dismisses any active selection
        topRow = 0 // typing pins us back to the live bottom
        onInput?.invoke(bytes)
    }

    companion object {
        const val DEFAULT_TEXT_SIZE_SP = 13f
        private const val MIN_DIM = 4
        private const val MENU_COPY = 1
        private const val MENU_SELECT_ALL = 2
        private const val SELECTION_HANDLE_COLOR = 0xFF4CAF50.toInt()
        private const val SELECTION_HANDLE_RADIUS_FRACTION = 0.35f
        private const val HANDLE_GRAB_COLS = 4
    }
}
