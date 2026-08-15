package com.t3tools.android.nativeapp

import expo.modules.t3terminal.TerminalResizePolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalResizePolicyTest {
  @Test
  fun first_size_applies_immediately_later_sizes_wait() {
    assertEquals(true, TerminalResizePolicy.shouldApplyImmediately(false))
    assertEquals(false, TerminalResizePolicy.shouldApplyImmediately(true))
    assertEquals(300L, TerminalResizePolicy.SETTLE_MS)
  }
}
