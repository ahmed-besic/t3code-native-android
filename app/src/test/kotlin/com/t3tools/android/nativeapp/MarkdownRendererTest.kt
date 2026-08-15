package com.t3tools.android.nativeapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MarkdownRendererTest {
  @Test
  fun wide_blocks_are_fences_and_tables_not_ordinary_text() {
    assertEquals(false, hasWideMarkdownBlock("just a message"))
    assertEquals(false, hasWideMarkdownBlock("I found it in `secteurs_intervention` earlier"))
    assertEquals(false, hasWideMarkdownBlock("a | b in a sentence"))
    assertEquals(false, hasWideMarkdownBlock("an em dash — and a rule\n\n---\n"))
    assertEquals(true, hasWideMarkdownBlock("before\n```\ncode\n```\nafter"))
    assertEquals(true, hasWideMarkdownBlock("before\n```ts\ncode\n```"))
    assertEquals(true, hasWideMarkdownBlock("before\n~~~\ncode\n~~~"))
    assertEquals(true, hasWideMarkdownBlock("   ```\ncode\n```"))
    assertEquals(true, hasWideMarkdownBlock("| a | b |\n| --- | --- |\n| 1 | 2 |"))
    assertEquals(true, hasWideMarkdownBlock("a | b\n:-- | --:\n1 | 2"))
  }

  @Test
  fun appending_output_returns_only_the_new_chunk() {
    assertEquals(" world", markdownAppendChunk("Hello", "Hello world"))
    assertEquals("", markdownAppendChunk("Hello", "Hello"))
  }

  @Test
  fun rewritten_output_requires_a_fresh_parse() {
    assertNull(markdownAppendChunk("Hello world", "Hello there"))
  }
}
