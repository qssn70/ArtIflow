package com.studysuit.aiqa.ui

import android.text.method.LinkMovementMethod
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.TextView
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.noties.markwon.Markwon
import io.noties.markwon.ext.latex.JLatexMathPlugin
import io.noties.markwon.ext.latex.JLatexMathTheme
import io.noties.markwon.inlineparser.MarkwonInlineParserPlugin
import kotlin.math.max
import kotlin.math.roundToInt

@Composable
internal fun MarkdownCardText(markdown: String, modifier: Modifier = Modifier) {
  MarkdownFormattedText(
    markdown = markdown,
    textStyle = MaterialTheme.typography.headlineSmall,
    textColor = Color(0xFF2F433C),
    textAlign = TextAlign.Center,
    modifier = modifier
  )
}

@Composable
internal fun MarkdownBodyText(markdown: String, modifier: Modifier = Modifier) {
  MarkdownFormattedText(
    markdown = markdown,
    textStyle = MaterialTheme.typography.bodyMedium,
    textColor = Color(0xFF2F433C),
    textAlign = TextAlign.Start,
    modifier = modifier
  )
}

@Composable
internal fun MarkdownFormattedText(
  markdown: String,
  textStyle: TextStyle,
  textColor: Color,
  textAlign: TextAlign,
  modifier: Modifier = Modifier
) {
  val effectiveLineHeight = remember(textStyle.fontSize) { markdownLineHeightFor(textStyle.fontSize) }
  val renderedStyle = remember(textStyle, effectiveLineHeight) {
    textStyle.copy(lineHeight = effectiveLineHeight)
  }

  val useLatexRenderer = remember(markdown) {
    containsLatexMarkdown(markdown)
  }
  if (useLatexRenderer) {
    val normalizedLatexMarkdown = remember(markdown) {
      normalizeLatexForMarkwon(markdown)
    }
    LatexMarkdownText(
      markdown = normalizedLatexMarkdown,
      textStyle = renderedStyle,
      textColor = textColor,
      textAlign = textAlign,
      modifier = modifier
    )
    return
  }

  val annotated = remember(markdown, textStyle.fontSize) {
    markdownToAnnotatedString(markdown, baseFontSize = textStyle.fontSize)
  }
  Text(
    text = annotated,
    style = renderedStyle,
    color = textColor,
    textAlign = textAlign,
    modifier = modifier
  )
}

@Composable
private fun LatexMarkdownText(
  markdown: String,
  textStyle: TextStyle,
  textColor: Color,
  textAlign: TextAlign,
  modifier: Modifier = Modifier
) {
  val context = LocalContext.current
  val density = LocalDensity.current

  val fontSizePx = remember(textStyle.fontSize, density) {
    with(density) {
      val fontSize = if (textStyle.fontSize == TextUnit.Unspecified) 16.sp else textStyle.fontSize
      fontSize.toPx()
    }
  }
  val inlineBaselineNudgePx = remember(density) {
    with(density) {
      1.dp.toPx().roundToInt().coerceAtLeast(1)
    }
  }

  val markwon = remember(context, fontSizePx) {
    Markwon.builder(context)
      .usePlugin(MarkwonInlineParserPlugin.create())
      .usePlugin(
        JLatexMathPlugin.create(
          fontSizePx * 0.9f,
          fontSizePx,
          object : JLatexMathPlugin.BuilderConfigure {
            override fun configureBuilder(builder: JLatexMathPlugin.Builder) {
              builder.inlinesEnabled(true)
              builder.theme().inlinePadding(JLatexMathTheme.Padding.of(0, 0, 0, inlineBaselineNudgePx))
            }
          }
        )
      )
      .build()
  }

  AndroidView(
    modifier = modifier,
    factory = { viewContext ->
      TextView(viewContext).apply {
        setTextIsSelectable(false)
        includeFontPadding = false
        movementMethod = LinkMovementMethod.getInstance()
      }
    },
    update = { textView ->
      textView.setTextColor(textColor.toArgb())
      textView.setTextSize(TypedValue.COMPLEX_UNIT_PX, fontSizePx)
      textView.textAlignment = View.TEXT_ALIGNMENT_GRAVITY
      textView.gravity = gravityForTextAlign(textAlign)
      markwon.setMarkdown(textView, markdown)
    }
  )
}

internal fun containsLatexMarkdown(markdown: String): Boolean {
  if (markdown.isBlank()) {
    return false
  }

  return markdownDoubleDollarBlockRegex.containsMatchIn(markdown) ||
    markdownDoubleDollarInlineRegex.containsMatchIn(markdown) ||
    markdownBracketBlockRegex.containsMatchIn(markdown) ||
    markdownParenInlineRegex.containsMatchIn(markdown) ||
    markdownSingleDollarInlineRegex.containsMatchIn(markdown)
}

internal fun normalizeLatexForMarkwon(markdown: String): String {
  if (markdown.isBlank()) {
    return markdown
  }

  var normalized = normalizeIndentedBracketBlocks(markdown)
  normalized = markdownBracketBlockRegex.replace(normalized) { match ->
    val equation = match.groupValues[1].trim()
    "$$\n$equation\n$$"
  }
  normalized = markdownParenInlineRegex.replace(normalized) { match ->
    val equation = match.groupValues[1].trim()
    "$$$equation$$"
  }
  normalized = markdownSingleDollarInlineRegex.replace(normalized) { match ->
    "$$${match.groupValues[1]}$$"
  }
  return normalized
}

private fun normalizeIndentedBracketBlocks(markdown: String): String {
  val normalizedSource = markdown.replace("\r\n", "\n").replace('\r', '\n')
  val lines = normalizedSource.split('\n')
  if (lines.none { line -> line.trim() == "\\[" }) {
    return normalizedSource
  }

  val output = mutableListOf<String>()
  var index = 0
  while (index < lines.size) {
    val line = lines[index]
    if (line.trim() != "\\[") {
      output += line
      index += 1
      continue
    }

    val indent = line.takeWhile { char -> char == ' ' || char == '\t' }
    val blockLines = mutableListOf<String>()
    var cursor = index + 1
    var closed = false
    while (cursor < lines.size) {
      val candidate = lines[cursor]
      if (candidate.trim() == "\\]") {
        closed = true
        break
      }
      blockLines += candidate
      cursor += 1
    }

    if (!closed) {
      output += line
      output += blockLines
      break
    }

    output += indent + "$$"
    stripCommonIndent(blockLines).forEach { contentLine ->
      output += if (contentLine.isBlank()) indent else indent + contentLine
    }
    output += indent + "$$"
    index = cursor + 1
  }

  return output.joinToString(separator = "\n")
}

private fun stripCommonIndent(lines: List<String>): List<String> {
  val nonBlank = lines.filter { line -> line.isNotBlank() }
  if (nonBlank.isEmpty()) {
    return lines
  }

  val commonIndent = nonBlank.minOf { line ->
    line.indexOfFirst { char -> !char.isWhitespace() }
      .let { firstNonBlank -> if (firstNonBlank == -1) line.length else firstNonBlank }
  }

  return lines.map { line ->
    if (line.isBlank()) {
      ""
    } else {
      line.drop(commonIndent).trimEnd()
    }
  }
}

private fun gravityForTextAlign(textAlign: TextAlign): Int {
  val horizontalGravity = when (textAlign) {
    TextAlign.Center -> Gravity.CENTER_HORIZONTAL
    TextAlign.End,
    TextAlign.Right -> Gravity.END

    TextAlign.Start,
    TextAlign.Left,
    TextAlign.Justify,
    TextAlign.Unspecified -> Gravity.START

    else -> Gravity.START
  }
  return horizontalGravity or Gravity.TOP
}

private fun markdownToAnnotatedString(markdown: String, baseFontSize: TextUnit): AnnotatedString {
  val normalized = markdown.replace("\r\n", "\n").replace('\r', '\n')
  val lines = normalized.split('\n')
  val codeStyle = SpanStyle(
    fontFamily = FontFamily.Monospace,
    background = Color(0x153B5D52)
  )

  return buildAnnotatedString {
    var inCodeBlock = false

    lines.forEachIndexed { index, rawLine ->
      val trimmed = rawLine.trimStart()
      if (trimmed.startsWith("```")) {
        inCodeBlock = !inCodeBlock
      } else if (inCodeBlock) {
        withStyle(codeStyle) {
          append(rawLine)
        }
      } else {
        val headingInfo = markdownHeading(trimmed)
        when {
          headingInfo != null -> {
            withStyle(
              SpanStyle(
                fontWeight = FontWeight.Bold,
                fontSize = markdownHeadingSize(headingInfo.second, baseFontSize)
              )
            ) {
              appendMarkdownInline(this, headingInfo.first)
            }
          }

          markdownBulletRegex.matches(trimmed) -> {
            withStyle(SpanStyle(fontWeight = FontWeight.Medium)) {
              append("• ")
            }
            appendMarkdownInline(this, trimmed.replaceFirst(markdownBulletRegex, ""))
          }

          markdownOrderedRegex.matches(trimmed) -> {
            val match = markdownOrderedRegex.find(trimmed)
            if (match != null) {
              append(match.groupValues[1])
              append(". ")
              appendMarkdownInline(this, trimmed.substring(match.range.last + 1).trimStart())
            } else {
              appendMarkdownInline(this, trimmed)
            }
          }

          trimmed.startsWith(">") -> {
            withStyle(SpanStyle(color = Color(0xFF5A7269))) {
              append("▎ ")
            }
            appendMarkdownInline(this, trimmed.removePrefix(">").trimStart())
          }

          else -> appendMarkdownInline(this, rawLine)
        }
      }

      if (index < lines.lastIndex) {
        append('\n')
      }
    }
  }
}

private fun markdownHeading(line: String): Pair<String, Int>? {
  return when {
    line.startsWith("### ") -> line.removePrefix("### ") to 3
    line.startsWith("## ") -> line.removePrefix("## ") to 2
    line.startsWith("# ") -> line.removePrefix("# ") to 1
    else -> null
  }
}

private fun markdownHeadingSize(level: Int, baseFontSize: TextUnit): TextUnit {
  val base = markdownFontSizeValue(baseFontSize)
  val delta = when (level) {
    1 -> 6f
    2 -> 4f
    else -> 2f
  }
  return (base + delta).sp
}

private fun markdownLineHeightFor(baseFontSize: TextUnit): TextUnit {
  val base = markdownFontSizeValue(baseFontSize)
  val headingPeak = base + 6f
  val lineHeight = max(base * 1.55f, headingPeak * 1.35f)
  return lineHeight.sp
}

private fun markdownFontSizeValue(baseFontSize: TextUnit): Float {
  return if (baseFontSize == TextUnit.Unspecified) 16f else baseFontSize.value
}

private fun appendMarkdownInline(builder: AnnotatedString.Builder, text: String) {
  var cursor = 0

  while (cursor < text.length) {
    when {
      text.startsWith("**", cursor) -> {
        val end = text.indexOf("**", cursor + 2)
        if (end > cursor + 2) {
          builder.withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
            appendMarkdownInline(this, text.substring(cursor + 2, end))
          }
          cursor = end + 2
        } else {
          builder.append("**")
          cursor += 2
        }
      }

      text.startsWith("*", cursor) -> {
        val end = text.indexOf("*", cursor + 1)
        if (end > cursor + 1) {
          builder.withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
            appendMarkdownInline(this, text.substring(cursor + 1, end))
          }
          cursor = end + 1
        } else {
          builder.append('*')
          cursor += 1
        }
      }

      text.startsWith("_", cursor) -> {
        val end = text.indexOf("_", cursor + 1)
        if (end > cursor + 1) {
          builder.withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
            appendMarkdownInline(this, text.substring(cursor + 1, end))
          }
          cursor = end + 1
        } else {
          builder.append('_')
          cursor += 1
        }
      }

      text.startsWith("~~", cursor) -> {
        val end = text.indexOf("~~", cursor + 2)
        if (end > cursor + 2) {
          builder.withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
            appendMarkdownInline(this, text.substring(cursor + 2, end))
          }
          cursor = end + 2
        } else {
          builder.append("~~")
          cursor += 2
        }
      }

      text.startsWith("`", cursor) -> {
        val end = text.indexOf('`', cursor + 1)
        if (end > cursor + 1) {
          builder.withStyle(
            SpanStyle(
              fontFamily = FontFamily.Monospace,
              background = Color(0x153B5D52)
            )
          ) {
            append(text.substring(cursor + 1, end))
          }
          cursor = end + 1
        } else {
          builder.append('`')
          cursor += 1
        }
      }

      text.startsWith("[", cursor) -> {
        val closeBracket = text.indexOf(']', cursor + 1)
        val hasParen = closeBracket != -1 && closeBracket + 1 < text.length && text[closeBracket + 1] == '('
        if (hasParen) {
          val closeParen = text.indexOf(')', closeBracket + 2)
          if (closeParen > closeBracket + 2) {
            val label = text.substring(cursor + 1, closeBracket)
            builder.withStyle(
              SpanStyle(
                color = Color(0xFF2F6D93),
                textDecoration = TextDecoration.Underline
              )
            ) {
              appendMarkdownInline(this, label)
            }
            cursor = closeParen + 1
          } else {
            builder.append('[')
            cursor += 1
          }
        } else {
          builder.append('[')
          cursor += 1
        }
      }

      else -> {
        builder.append(text[cursor])
        cursor += 1
      }
    }
  }
}

private val markdownBulletRegex = Regex("^[-*+]\\s+")
private val markdownOrderedRegex = Regex("^(\\d+)\\.\\s+")
private val markdownDoubleDollarBlockRegex = Regex("(?s)(^|\\n)\\$\\$\\s*.+?\\s*\\$\\$($|\\n)")
private val markdownDoubleDollarInlineRegex = Regex("(?<!\\\\)\\$\\$[^\\n]+?\\$\\$")
private val markdownSingleDollarInlineRegex = Regex("(?<!\\\\)(?<!\\$)\\$([^$\\n]+)\\$(?!\\$)")
private val markdownBracketBlockRegex = Regex("(?s)\\\\\\[\\s*(.+?)\\s*\\\\\\]")
private val markdownParenInlineRegex = Regex("(?s)\\\\\\((.+?)\\\\\\)")
