package com.t3tools.android.nativeapp

internal object ConnectionPolicy {
  const val PROBE_TIMEOUT_MS = 3_000L
  const val STABLE_LEASE_MS = 30_000L
  private val retryDelays = longArrayOf(3_000, 4_000, 8_000, 16_000)

  fun retryDelay(attempt: Int) = retryDelays[attempt.coerceIn(0, retryDelays.lastIndex)]

  @Suppress("UNUSED_PARAMETER")
  fun resumeAction(backgroundDurationMs: Long) = ResumeAction.Probe

  fun preserveShellOnReconnect(shellSynchronized: Boolean) = shellSynchronized
}

internal enum class ResumeAction { Probe, Reconnect }
