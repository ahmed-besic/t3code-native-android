package com.t3tools.android.nativeapp

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import expo.modules.t3terminal.TerminalSurfaceView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TerminalSurfaceViewTest {
  @Test
  fun creates_feeds_resizes_and_destroys_the_shared_renderer() {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    instrumentation.runOnMainSync {
      var measuredGrid: Pair<Int, Int>? = null
      val view = TerminalSurfaceView(instrumentation.targetContext).apply {
        terminalKey = "renderer-smoke"
        onResize = { cols, rows -> measuredGrid = cols to rows }
        measure(exactly(1080), exactly(1600))
        layout(0, 0, 1080, 1600)
        reset("\u001b[32mready\u001b[0m\r\n")
        append("unicode 🙂\r\n")
        fontSize = 11f
      }

      assertEquals("t3-terminal-renderer-smoke", view.contentDescription)
      assertTrue("Initial layout must create the terminal grid", measuredGrid != null)
      view.cleanup()
    }
  }

  @Test
  fun scroll_gesture_does_not_focus_the_keyboard_but_a_tap_does() {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    instrumentation.runOnMainSync {
      val view = TerminalSurfaceView(instrumentation.targetContext).apply {
        autoFocus = false
        measure(exactly(1080), exactly(1600))
        layout(0, 0, 1080, 1600)
      }
      val downTime = SystemClock.uptimeMillis()

      view.dispatchTouchEvent(
        MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, 540f, 800f, 0),
      )
      view.dispatchTouchEvent(
        MotionEvent.obtain(downTime, downTime + 20, MotionEvent.ACTION_MOVE, 540f, 400f, 0),
      )
      view.dispatchTouchEvent(
        MotionEvent.obtain(downTime, downTime + 40, MotionEvent.ACTION_UP, 540f, 400f, 0),
      )

      assertFalse(view.hasFocus())

      val tapTime = downTime + 100
      view.dispatchTouchEvent(
        MotionEvent.obtain(tapTime, tapTime, MotionEvent.ACTION_DOWN, 540f, 800f, 0),
      )
      view.dispatchTouchEvent(
        MotionEvent.obtain(tapTime, tapTime + 20, MotionEvent.ACTION_UP, 540f, 800f, 0),
      )

      assertTrue(view.hasFocus())
      view.cleanup()
    }
  }

  @Test
  fun ime_committed_text_reaches_terminal_input() {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    instrumentation.runOnMainSync {
      val inputs = mutableListOf<String>()
      val view = TerminalSurfaceView(instrumentation.targetContext).apply {
        autoFocus = false
        onInput = inputs::add
        requestKeyboardFocus()
      }

      val connection = requireNotNull(view.findFocus()?.onCreateInputConnection(EditorInfo()))
      connection.commitText("terminal input", 1)

      assertEquals(listOf("terminal input"), inputs)
      view.cleanup()
    }
  }

  private fun exactly(size: Int) = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)
}
