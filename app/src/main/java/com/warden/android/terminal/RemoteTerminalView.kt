package com.warden.android.terminal

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import com.termux.terminal.KeyHandler
import com.termux.view.TerminalRenderer
import java.nio.charset.StandardCharsets
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
 * [KeyHandler]), resize on layout, and touch scrollback. Text selection/mouse
 * tracking are intentionally left for a later pass.
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
        // No active selection → pass the "none" sentinels for all four bounds.
        renderer.render(s.emulator, canvas, topRow, -1, -1, -1, -1)
    }

    private fun clampTopRow(s: RemoteTerminalSession) {
        val history = s.emulator.screen.activeTranscriptRows
        if (topRow < -history) topRow = -history
        if (topRow > 0) topRow = 0
    }

    // ---- touch: scrollback ----

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                showKeyboard()
                return true
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float,
            ): Boolean {
                scrollRemainder += distanceY
                val lineHeight = renderer.fontLineSpacing
                val rows = (scrollRemainder / lineHeight).toInt()
                if (rows != 0) {
                    scrollRemainder -= rows * lineHeight
                    // Dragging up (positive distanceY) reveals older lines.
                    topRow -= rows
                    invalidate()
                }
                return true
            }
        },
    )

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isFocused) requestFocus()
        return gestureDetector.onTouchEvent(event)
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
        topRow = 0 // typing pins us back to the live bottom
        onInput?.invoke(bytes)
    }

    companion object {
        const val DEFAULT_TEXT_SIZE_SP = 13f
        private const val MIN_DIM = 4
    }
}
