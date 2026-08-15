package com.t3tools.android.nativeapp

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import com.mikepenz.markdown.compose.components.MarkdownComponents
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.compose.elements.MarkdownTableBasicText
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.MarkdownAnimations
import com.mikepenz.markdown.model.MarkdownColors
import com.mikepenz.markdown.model.MarkdownDimens
import com.mikepenz.markdown.model.MarkdownPadding
import com.mikepenz.markdown.model.MarkdownTypography
import com.mikepenz.markdown.model.markdownAnimations
import com.mikepenz.markdown.model.markdownDimens
import com.mikepenz.markdown.model.markdownPadding
import com.mikepenz.markdown.model.rememberMarkdownState
import com.mikepenz.markdown.model.rememberStreamingMarkdownState
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.findChildOfType
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMTokenTypes

private val MarkdownBody = Color(0xFFE5E5E5)
private val MarkdownStrong = Color(0xFFF5F5F5)
private val MarkdownMuted = Color(0xFFB8BCC2)
private val MarkdownLink = Color(0xFF60A5FA)
private val MarkdownCodeBackground = Color(0xFF18181B)
private val MarkdownDivider = Color(0x14FFFFFF)

internal fun markdownAppendChunk(previous: String, current: String): String? =
  current.takeIf { it.startsWith(previous) }?.substring(previous.length)

private val FencedCodeBlock = Regex("(?m)^ {0,3}(?:```|~~~)")

internal fun hasWideMarkdownBlock(text: String): Boolean {
  if (FencedCodeBlock.containsMatchIn(text)) return true
  if (!text.contains('|')) return false
  return text.lineSequence().any(::isMarkdownTableDelimiterRow)
}

internal fun isMarkdownTableDelimiterRow(line: String): Boolean {
  val trimmed = line.trim()
  if ('|' !in trimmed || '-' !in trimmed) return false
  return trimmed.all { it == '|' || it == '-' || it == ':' || it == ' ' || it == '\t' }
}

private data class T3MarkdownStyle(
  val colors: MarkdownColors,
  val typography: MarkdownTypography,
  val padding: MarkdownPadding,
  val dimens: MarkdownDimens,
  val components: MarkdownComponents,
  val animations: MarkdownAnimations,
)

@Composable
private fun t3MarkdownStyle(): T3MarkdownStyle {
  val appearance = LocalT3Appearance.current
  val sizes = resolveMarkdownFontSizes(appearance.baseFontSize)
  val codeLineHeight = maxOf(appearance.codeFontSize + 6f, appearance.codeFontSize * 1.45f)
  val body = TextStyle(
    color = MarkdownBody,
    fontSize = sizes.body.sp,
    lineHeight = sizes.bodyLineHeight.sp,
  )
  val heading = body.copy(color = MarkdownStrong, fontWeight = FontWeight.Bold)
  val components = remember(appearance.codeWordBreak) {
    markdownComponents(
      codeFence = { model ->
        MarkdownCodeFence(model.content, model.node, model.typography.code) { code, language, style ->
          T3MarkdownCodeBlock(code, language, style, appearance.codeWordBreak)
        }
      },
      codeBlock = { model ->
        MarkdownCodeBlock(model.content, model.node, model.typography.code) { code, language, style ->
          T3MarkdownCodeBlock(code, language, style, appearance.codeWordBreak)
        }
      },
      table = { model -> T3MarkdownTable(model) },
    )
  }
  return T3MarkdownStyle(
    colors = markdownColor(
      text = MarkdownBody,
      codeBackground = MarkdownCodeBackground,
      inlineCodeBackground = Color(0x0FFFFFFF),
      dividerColor = MarkdownDivider,
      tableBackground = Color(0x08FFFFFF),
    ),
    typography = markdownTypography(
      h1 = heading.copy(fontSize = sizes.h1.sp, lineHeight = (sizes.h1 + 6f).sp),
      h2 = heading.copy(fontSize = sizes.h2.sp, lineHeight = (sizes.h2 + 6f).sp),
      h3 = heading.copy(fontSize = sizes.h3.sp, lineHeight = (sizes.h3 + 6f).sp),
      h4 = heading,
      h5 = heading,
      h6 = heading,
      text = body,
      paragraph = body,
      ordered = body,
      bullet = body,
      list = body,
      quote = body,
      code = body.copy(
        fontFamily = FontFamily.Monospace,
        fontSize = appearance.codeFontSize.sp,
        lineHeight = codeLineHeight.sp,
      ),
      inlineCode = body.copy(
        color = MarkdownMuted,
        fontFamily = FontFamily.Monospace,
        fontSize = appearance.codeFontSize.sp,
      ),
      textLink = TextLinkStyles(
        style = SpanStyle(color = MarkdownLink, textDecoration = TextDecoration.Underline),
      ),
      table = body.copy(fontSize = sizes.small.sp, lineHeight = (sizes.small + 4f).sp),
    ),
    padding = markdownPadding(
      block = 5.dp,
      list = 2.dp,
      listItemTop = 0.dp,
      listItemBottom = 3.dp,
      listIndent = 12.dp,
      codeBlock = PaddingValues(12.dp),
      blockQuote = PaddingValues(vertical = 6.dp),
      blockQuoteText = PaddingValues(start = 11.dp, top = 2.dp, bottom = 2.dp),
      blockQuoteBar = PaddingValues.Absolute(top = 2.dp, bottom = 2.dp),
    ),
    dimens = markdownDimens(
      dividerThickness = 1.dp,
      codeBackgroundCornerSize = 10.dp,
      blockQuoteThickness = 2.dp,
      tableCellWidth = 140.dp,
      tableCellPadding = 8.dp,
      tableCornerSize = 10.dp,
    ),
    components = components,
    animations = markdownAnimations(animateTextSize = { this }),
  )
}

@Composable
internal fun T3Markdown(
  markdown: String,
  streaming: Boolean,
  modifier: Modifier = Modifier,
  fillWidth: Boolean = true,
) {
  val style = t3MarkdownStyle()
  val useStreamingParser = remember { streaming }
  SelectionContainer(modifier) {
    if (useStreamingParser) StreamingMarkdown(markdown, style, fillWidth)
    else StaticMarkdown(markdown, style, fillWidth)
  }
}

@Composable
private fun StaticMarkdown(markdown: String, style: T3MarkdownStyle, fillWidth: Boolean) {
  val state = rememberMarkdownState(markdown, retainState = true)
  val width = if (fillWidth) Modifier.fillMaxWidth() else Modifier
  Markdown(
    markdownState = state,
    colors = style.colors,
    typography = style.typography,
    padding = style.padding,
    dimens = style.dimens,
    components = style.components,
    animations = style.animations,
    modifier = width,
    error = {
      Text(markdown, style = style.typography.text, modifier = width)
    },
  )
}

@Composable
private fun StreamingMarkdown(markdown: String, style: T3MarkdownStyle, fillWidth: Boolean) {
  val state = rememberStreamingMarkdownState()
  var previous by remember { mutableStateOf("") }
  var appendOnly by remember { mutableStateOf(true) }
  LaunchedEffect(markdown) {
    val chunk = markdownAppendChunk(previous, markdown)
    if (chunk == null) {
      appendOnly = false
    } else {
      chunk.takeIf(String::isNotEmpty)?.let { state.append(it) }
    }
    previous = markdown
  }
  if (appendOnly) {
    Markdown(
      streamingMarkdownState = state,
      colors = style.colors,
      typography = style.typography,
      padding = style.padding,
      dimens = style.dimens,
      components = style.components,
      animations = style.animations,
      modifier = if (fillWidth) Modifier.fillMaxWidth() else Modifier,
    )
  } else {
    StaticMarkdown(markdown, style, fillWidth)
  }
}

@Composable
private fun T3MarkdownTable(model: MarkdownComponentModel) {
  val header = model.node.findChildOfType(GFMElementTypes.HEADER) ?: return
  val columns = header.children.filter { it.type == GFMTokenTypes.CELL }
  if (columns.isEmpty()) return
  val rows = model.node.children.filter { it.type == GFMElementTypes.ROW }
  val cellStyle = model.typography.table
  Row(
    modifier = Modifier
      .horizontalScroll(rememberScrollState())
      .padding(vertical = 4.dp),
  ) {
    columns.forEachIndexed { columnIndex, headerCell ->
      Column(Modifier.width(IntrinsicSize.Max)) {
        T3MarkdownTableCell(
          content = model.content,
          cell = headerCell,
          style = cellStyle.copy(fontWeight = FontWeight.Bold),
        )
        HorizontalDivider(color = MarkdownDivider)
        rows.forEach { row ->
          val cell = row.children.filter { it.type == GFMTokenTypes.CELL }.getOrNull(columnIndex)
          if (cell != null) {
            T3MarkdownTableCell(
              content = model.content,
              cell = cell,
              style = cellStyle,
            )
            HorizontalDivider(color = MarkdownDivider)
          }
        }
      }
    }
  }
}

@Composable
private fun T3MarkdownTableCell(
  content: String,
  cell: ASTNode,
  style: TextStyle,
) {
  Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
    MarkdownTableBasicText(
      content = content,
      cell = cell,
      style = style,
      maxLines = Int.MAX_VALUE,
      overflow = TextOverflow.Visible,
    )
  }
}

@Composable
private fun T3MarkdownCodeBlock(
  code: String,
  language: String?,
  style: TextStyle,
  wordBreak: Boolean,
) {
  val context = LocalContext.current
  Surface(
    shape = RoundedCornerShape(10.dp),
    color = MarkdownCodeBackground,
    border = BorderStroke(1.dp, MarkdownDivider),
    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
  ) {
    Column {
      Row(
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Text(
          text = language?.uppercase()?.takeIf(String::isNotBlank) ?: "CODE",
          color = MarkdownMuted,
          fontFamily = FontFamily.Monospace,
          fontSize = 10.sp,
        )
        IconButton(
          onClick = {
            context.getSystemService(ClipboardManager::class.java)
              .setPrimaryClip(ClipData.newPlainText("Code", code))
          },
          modifier = Modifier.size(32.dp),
        ) {
          Icon(
            Icons.Rounded.ContentCopy,
            contentDescription = "Copy code",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
      Text(
        text = code,
        style = style,
        modifier = (if (wordBreak) Modifier.fillMaxWidth() else {
          Modifier.horizontalScroll(rememberScrollState())
        }).padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
      )
    }
  }
}
