package com.t3tools.android.nativeapp

import java.net.URI

internal const val PROJECT_FAVICON_MISSING_MARKER = "project-favicon-missing"
internal const val PROJECT_FAVICON_STALE_BEFORE_EXPIRY_MS = 5 * 60_000L

internal data class ProjectFaviconRecord(
  val relativeUrl: String,
  val expiresAt: Long,
  val httpBaseUrl: String,
)

internal fun joinAssetUrl(httpBaseUrl: String, relativeUrl: String): String {
  if (relativeUrl.startsWith("http://") || relativeUrl.startsWith("https://")) return relativeUrl
  val base = httpBaseUrl.trimEnd('/')
  return URI("$base/").resolve(relativeUrl.removePrefix("/")).toString()
}

internal fun isProjectFaviconMissing(relativeUrl: String): Boolean {
  val last = relativeUrl.substringAfterLast('/').substringBefore('?')
  return last == PROJECT_FAVICON_MISSING_MARKER
}

internal fun projectFaviconNeedsRefresh(
  record: ProjectFaviconRecord,
  nowMs: Long,
  currentHttpBaseUrl: String,
): Boolean {
  if (record.httpBaseUrl.trimEnd('/') != currentHttpBaseUrl.trimEnd('/')) return true
  if (isProjectFaviconMissing(record.relativeUrl)) return false
  return nowMs >= record.expiresAt - PROJECT_FAVICON_STALE_BEFORE_EXPIRY_MS
}

internal fun publishedProjectFaviconUrl(
  record: ProjectFaviconRecord,
  failedAbsoluteUrl: String? = null,
): String? {
  if (isProjectFaviconMissing(record.relativeUrl)) return null
  val url = joinAssetUrl(record.httpBaseUrl, record.relativeUrl)
  if (url == failedAbsoluteUrl) return null
  return url
}
