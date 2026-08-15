package com.t3tools.android.nativeapp

import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionPolicyTest {
  @Test
  fun caps_retry_delay_and_resets_from_first_attempt() {
    assertEquals(3_000, ConnectionPolicy.retryDelay(0))
    assertEquals(4_000, ConnectionPolicy.retryDelay(1))
    assertEquals(16_000, ConnectionPolicy.retryDelay(99))
  }

  @Test
  fun always_probes_on_resume_instead_of_forcing_a_reconnect() {
    assertEquals(ResumeAction.Probe, ConnectionPolicy.resumeAction(0))
    assertEquals(ResumeAction.Probe, ConnectionPolicy.resumeAction(9_999))
    assertEquals(ResumeAction.Probe, ConnectionPolicy.resumeAction(10_000))
    assertEquals(ResumeAction.Probe, ConnectionPolicy.resumeAction(60_000))
  }

  @Test
  fun keeps_a_synchronized_shell_across_reconnect() {
    assertEquals(true, ConnectionPolicy.preserveShellOnReconnect(true))
    assertEquals(false, ConnectionPolicy.preserveShellOnReconnect(false))
  }
}
