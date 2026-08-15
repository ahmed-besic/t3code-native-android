package com.t3tools.android.nativeapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectFaviconTest {
  @Test
  fun joins_relative_asset_urls_against_the_environment_origin() {
    assertEquals(
      "http://host.example:13773/api/assets/token/icon.svg",
      joinAssetUrl("http://host.example:13773/", "/api/assets/token/icon.svg"),
    )
    assertEquals(
      "http://host.example:13773/api/assets/token/icon.svg",
      joinAssetUrl("http://host.example:13773", "api/assets/token/icon.svg"),
    )
    assertEquals(
      "https://cdn.example/icon.svg",
      joinAssetUrl("http://host.example:13773/", "https://cdn.example/icon.svg"),
    )
  }

  @Test
  fun ignores_the_server_missing_marker() {
    val record = ProjectFaviconRecord(
      relativeUrl = "/api/assets/token/$PROJECT_FAVICON_MISSING_MARKER",
      expiresAt = 1_000L,
      httpBaseUrl = "http://host.example:13773/",
    )

    assertTrue(isProjectFaviconMissing(record.relativeUrl))
    assertNull(publishedProjectFaviconUrl(record))
    assertFalse(
      projectFaviconNeedsRefresh(record, nowMs = 10_000L, currentHttpBaseUrl = record.httpBaseUrl),
    )
  }

  @Test
  fun remints_when_expired_stale_or_the_origin_changes() {
    val record = ProjectFaviconRecord(
      relativeUrl = "/api/assets/token/icon.svg",
      expiresAt = 30 * 60_000L,
      httpBaseUrl = "http://host.example:13773/",
    )

    assertFalse(
      projectFaviconNeedsRefresh(record, nowMs = 10 * 60_000L, currentHttpBaseUrl = record.httpBaseUrl),
    )
    assertTrue(
      projectFaviconNeedsRefresh(
        record,
        nowMs = record.expiresAt - PROJECT_FAVICON_STALE_BEFORE_EXPIRY_MS,
        currentHttpBaseUrl = record.httpBaseUrl,
      ),
    )
    assertTrue(
      projectFaviconNeedsRefresh(
        record,
        nowMs = 10 * 60_000L,
        currentHttpBaseUrl = "http://100.64.0.2:13773/",
      ),
    )
  }

  @Test
  fun hides_a_url_after_the_image_load_fails() {
    val record = ProjectFaviconRecord(
      relativeUrl = "/api/assets/token/icon.svg",
      expiresAt = 30 * 60_000L,
      httpBaseUrl = "http://host.example:13773/",
    )
    val url = publishedProjectFaviconUrl(record)

    assertEquals("http://host.example:13773/api/assets/token/icon.svg", url)
    assertNull(publishedProjectFaviconUrl(record, failedAbsoluteUrl = url))
    assertEquals(
      "http://host.example:13773/api/assets/token/icon-2.svg",
      publishedProjectFaviconUrl(
        record.copy(relativeUrl = "/api/assets/token/icon-2.svg"),
        failedAbsoluteUrl = url,
      ),
    )
  }
}
