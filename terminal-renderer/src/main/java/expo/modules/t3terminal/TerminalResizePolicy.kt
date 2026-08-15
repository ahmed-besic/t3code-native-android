package expo.modules.t3terminal

object TerminalResizePolicy {
  const val SETTLE_MS = 300L

  fun shouldApplyImmediately(hasPublishedSize: Boolean) = !hasPublishedSize
}
