package expo.modules.t3terminal

import android.content.Context
import android.graphics.Color
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout

class TerminalSurfaceView(context: Context) : FrameLayout(context) {
  private val terminalCanvas = TerminalCanvasView(context)
  private val inputView = TerminalKeyInputView(context)
  private val resizeSettle = Runnable { applyResize() }
  private var terminalHandle = 0L
  private var fedBuffer = ""
  private var cols = 0
  private var rows = 0
  private var hasPublishedSize = false
  private var isCleanedUp = false
  private var backgroundColorValue = Color.parseColor("#24292E")
  private var foregroundColorValue = Color.parseColor("#D1D5DA")
  private var cursorColorValue = Color.parseColor("#009FFF")
  private var paletteColors = IntArray(0)

  var onInput: ((String) -> Unit)? = null
  var onResize: ((cols: Int, rows: Int) -> Unit)? = null

  var terminalKey: String = ""
    set(value) {
      if (field == value) return
      field = value
      contentDescription = "t3-terminal-$value"
      recreateTerminal()
      if (autoFocus) post(::requestKeyboardFocus)
    }

  var initialBuffer: String = ""
    set(value) {
      if (field == value) return
      field = value
      feedPendingBuffer()
    }

  var fontSize: Float = 10f
    set(value) {
      if (field == value) return
      field = value
      terminalCanvas.fontSizeSp = value
      emitResize()
    }

  var themeConfig: String = ""
    set(value) {
      if (field == value) return
      field = value
      parseThemeConfig(value)
      applyTheme()
    }

  var autoFocus: Boolean = true
    set(value) {
      field = value
      if (value) requestKeyboardFocus() else dismissKeyboard()
    }

  var backgroundColorHex: String = "#24292E"
    set(value) {
      if (field == value) return
      field = value
      backgroundColorValue = parseColor(value, backgroundColorValue)
      applyTheme()
    }

  var foregroundColorHex: String = "#D1D5DA"
    set(value) {
      if (field == value) return
      field = value
      foregroundColorValue = parseColor(value, foregroundColorValue)
      applyTheme()
    }

  init {
    terminalCanvas.fontSizeSp = fontSize
    terminalCanvas.onRequestKeyboard = ::requestKeyboardFocus
    terminalCanvas.onScrollRows = { delta ->
      if (terminalHandle != 0L) {
        GhosttyBridge.nativeScroll(terminalHandle, delta)
        renderSnapshot()
      }
    }
    terminalCanvas.onCellMetricsChanged = ::emitResize
    terminalCanvas.selectionDelegate = object : TerminalSelectionDelegate {
      override fun selectWordAt(col: Int, row: Int): Boolean {
        if (terminalHandle == 0L) return false
        val selected = GhosttyBridge.nativeSelectWordAt(terminalHandle, col, row)
        if (selected) renderSnapshot()
        return selected
      }

      override fun extendSelection(anchorCol: Int, anchorRow: Int, col: Int, row: Int) {
        if (terminalHandle == 0L) return
        GhosttyBridge.nativeExtendSelection(terminalHandle, anchorCol, anchorRow, col, row)
        renderSnapshot()
      }

      override fun selectAll(): Boolean {
        if (terminalHandle == 0L) return false
        val selected = GhosttyBridge.nativeSelectAll(terminalHandle)
        if (selected) renderSnapshot()
        return selected
      }

      override fun clearSelection() {
        if (terminalHandle == 0L) return
        GhosttyBridge.nativeClearSelection(terminalHandle)
        renderSnapshot()
      }

      override fun selectionText(): String? = if (terminalHandle == 0L) {
        null
      } else {
        GhosttyBridge.nativeGetSelectionText(terminalHandle)?.toString(Charsets.UTF_8)
      }
    }

    configureInputView()
    addView(
      terminalCanvas,
      LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
    )
    addView(inputView, LayoutParams(1, 1))
    applyTheme()
  }

  override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
    super.onSizeChanged(width, height, oldWidth, oldHeight)
    if (width != oldWidth || height != oldHeight) emitResize()
  }

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    if (autoFocus) post(::requestKeyboardFocus)
  }

  override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
    super.onLayout(changed, left, top, right, bottom)
    if (changed) emitResize()
  }

  fun append(data: String) {
    if (data.isEmpty()) return
    if (terminalHandle == 0L) {
      initialBuffer += data
      return
    }
    feed(data)
  }

  fun reset(buffer: String) {
    if (terminalHandle == 0L) {
      initialBuffer = buffer
      return
    }
    destroyTerminal()
    initialBuffer = buffer
    createTerminal()
    feedPendingBuffer()
    renderSnapshot()
  }

  fun clear() {
    reset("")
  }

  fun requestKeyboardFocus() {
    inputView.requestFocus()
    val inputMethodManager = context.getSystemService(Context.INPUT_METHOD_SERVICE)
      as? InputMethodManager
    inputMethodManager?.showSoftInput(inputView, 0)
  }

  fun dismissKeyboard() {
    inputView.clearFocus()
    val inputMethodManager = context.getSystemService(Context.INPUT_METHOD_SERVICE)
      as? InputMethodManager
    inputMethodManager?.hideSoftInputFromWindow(windowToken, 0)
  }

  fun cleanup() {
    if (isCleanedUp) return
    isCleanedUp = true
    removeCallbacks(resizeSettle)
    inputView.setOnKeyListener(null)
    terminalCanvas.onScrollRows = null
    terminalCanvas.onRequestKeyboard = null
    terminalCanvas.onCellMetricsChanged = null
    terminalCanvas.selectionDelegate = null
    onInput = null
    onResize = null
    destroyTerminal()
  }

  private fun configureInputView() {
    inputView.isFocusable = true
    inputView.isFocusableInTouchMode = true
    inputView.setOnKeyListener { _, keyCode, event ->
      if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
      when {
        keyCode == KeyEvent.KEYCODE_DEL -> {
          emitKeys("\u007F")
          true
        }
        keyCode == KeyEvent.KEYCODE_ENTER -> {
          emitKeys("\r")
          true
        }
        event.isCtrlPressed && keyCode in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> {
          emitKeys((keyCode - KeyEvent.KEYCODE_A + 1).toChar().toString())
          true
        }
        else -> false
      }
    }
  }

  private fun emitKeys(data: String) {
    val normalized = data.replace("\n", "\r")
    if (normalized.isNotEmpty()) onInput?.invoke(normalized)
  }

  private fun emitResize() {
    if (isCleanedUp) return
    if (TerminalResizePolicy.shouldApplyImmediately(hasPublishedSize)) {
      applyResize()
      return
    }
    removeCallbacks(resizeSettle)
    postDelayed(resizeSettle, TerminalResizePolicy.SETTLE_MS)
  }

  @Suppress("ComplexCondition")
  private fun applyResize() {
    if (width <= 0 || height <= 0 || terminalCanvas.width <= 0 ||
      terminalCanvas.height <= 0 || isCleanedUp
    ) return
    val nextCols = (terminalCanvas.usableWidth() / terminalCanvas.cellWidthPx)
      .toInt().coerceIn(2, 400)
    val nextRows = (terminalCanvas.usableHeight() / terminalCanvas.cellHeightPx)
      .toInt().coerceIn(2, 200)
    if (nextCols == cols && nextRows == rows && terminalHandle != 0L) {
      hasPublishedSize = true
      return
    }
    cols = nextCols
    rows = nextRows
    hasPublishedSize = true
    val response = if (terminalHandle == 0L) {
      createTerminal()
      ByteArray(0)
    } else {
      GhosttyBridge.nativeResize(
        terminalHandle,
        cols,
        rows,
        terminalCanvas.cellWidthPx.toInt(),
        terminalCanvas.cellHeightPx.toInt(),
      )
    }
    emitResponse(response)
    onResize?.invoke(cols, rows)
    feedPendingBuffer()
    renderSnapshot()
  }

  private fun createTerminal() {
    if (terminalHandle != 0L || cols <= 0 || rows <= 0 || isCleanedUp) return
    terminalHandle = GhosttyBridge.nativeCreate(
      cols,
      rows,
      terminalCanvas.cellWidthPx.toInt(),
      terminalCanvas.cellHeightPx.toInt(),
      foregroundColorValue,
      backgroundColorValue,
      cursorColorValue,
      paletteColors,
    )
    fedBuffer = ""
  }

  private fun recreateTerminal() {
    if (terminalHandle == 0L) return
    destroyTerminal()
    createTerminal()
    feedPendingBuffer()
    renderSnapshot()
  }

  private fun destroyTerminal() {
    if (terminalHandle == 0L) return
    GhosttyBridge.nativeDestroy(terminalHandle)
    terminalHandle = 0L
    fedBuffer = ""
    terminalCanvas.resetSelectionState()
  }

  private fun feedPendingBuffer() {
    if (terminalHandle == 0L || initialBuffer == fedBuffer) return
    if (!initialBuffer.startsWith(fedBuffer)) {
      recreateTerminal()
      if (terminalHandle == 0L) return
    }
    val suffix = initialBuffer.substring(fedBuffer.length)
    if (suffix.isNotEmpty()) feed(suffix)
    fedBuffer = initialBuffer
    renderSnapshot()
  }

  private fun feed(data: String) {
    emitResponse(GhosttyBridge.nativeFeed(terminalHandle, data.toByteArray(Charsets.UTF_8)))
    if (terminalCanvas.hasActiveSelection()) {
      GhosttyBridge.nativeClearSelection(terminalHandle)
      terminalCanvas.resetSelectionState()
    }
    renderSnapshot()
  }

  private fun renderSnapshot() {
    if (terminalHandle == 0L) return
    TerminalFrame.decode(GhosttyBridge.nativeSnapshot(terminalHandle))?.let(terminalCanvas::setFrame)
  }

  private fun emitResponse(response: ByteArray) {
    if (response.isNotEmpty()) onInput?.invoke(response.toString(Charsets.UTF_8))
  }

  private fun applyTheme() {
    setBackgroundColor(backgroundColorValue)
    terminalCanvas.setBackgroundColor(backgroundColorValue)
    if (terminalHandle != 0L) {
      GhosttyBridge.nativeSetTheme(
        terminalHandle,
        foregroundColorValue,
        backgroundColorValue,
        cursorColorValue,
        paletteColors,
      )
      renderSnapshot()
    }
  }

  private inner class TerminalKeyInputView(context: Context) : View(context) {
    override fun onCheckIsTextEditor() = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
      outAttrs.inputType = InputType.TYPE_NULL
      outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or
        EditorInfo.IME_FLAG_NO_FULLSCREEN or
        EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or
        EditorInfo.IME_ACTION_NONE
      return TerminalInputConnection(this)
    }
  }

  private inner class TerminalInputConnection(target: View) : BaseInputConnection(target, false) {
    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
      text?.toString()?.let(::emitKeys)
      return true
    }

    override fun setComposingText(text: CharSequence?, newCursorPosition: Int) = true

    override fun finishComposingText() = true

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
      if (beforeLength > 0) emitKeys("\u007F")
      return true
    }

    override fun performEditorAction(editorAction: Int): Boolean {
      emitKeys("\r")
      return true
    }

    override fun sendKeyEvent(event: KeyEvent): Boolean {
      if (event.action != KeyEvent.ACTION_DOWN) return true
      when (event.keyCode) {
        KeyEvent.KEYCODE_DEL -> emitKeys("\u007F")
        KeyEvent.KEYCODE_ENTER -> emitKeys("\r")
        KeyEvent.KEYCODE_TAB -> emitKeys("\t")
        KeyEvent.KEYCODE_DPAD_UP -> emitKeys("\u001b[A")
        KeyEvent.KEYCODE_DPAD_DOWN -> emitKeys("\u001b[B")
        KeyEvent.KEYCODE_DPAD_RIGHT -> emitKeys("\u001b[C")
        KeyEvent.KEYCODE_DPAD_LEFT -> emitKeys("\u001b[D")
        KeyEvent.KEYCODE_ESCAPE -> emitKeys("\u001b")
        else -> {
          if (event.isCtrlPressed && event.keyCode in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z) {
            emitKeys((event.keyCode - KeyEvent.KEYCODE_A + 1).toChar().toString())
          } else {
            val unicode = event.unicodeChar
            if (unicode != 0) emitKeys(unicode.toChar().toString())
            else return super.sendKeyEvent(event)
          }
        }
      }
      return true
    }
  }

  @Suppress("LoopWithTooManyJumpStatements")
  private fun parseThemeConfig(config: String) {
    val palette = sortedMapOf<Int, Int>()
    for (line in config.lineSequence()) {
      val parts = line.split('=', limit = 2)
      if (parts.size != 2) continue
      val key = parts[0].trim()
      val value = parts[1].trim()
      when (key) {
        "cursor-color" -> cursorColorValue = parseColor(value, cursorColorValue)
        "palette" -> {
          val paletteParts = value.split('=', limit = 2)
          val index = paletteParts.firstOrNull()?.trim()?.toIntOrNull() ?: continue
          val color = paletteParts.getOrNull(1)?.trim() ?: continue
          if (index in 0..255) palette[index] = parseColor(color, foregroundColorValue)
        }
      }
    }
    if (palette.isNotEmpty()) {
      val lastIndex = palette.lastKey()
      paletteColors = IntArray(lastIndex + 1) { palette[it] ?: foregroundColorValue }
    }
  }

  private fun parseColor(value: String, fallback: Int): Int = try {
    Color.parseColor(value)
  } catch (_: IllegalArgumentException) {
    fallback
  }
}
