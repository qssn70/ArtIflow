package com.studysuit.aiqa.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import androidx.compose.ui.unit.sp
import kotlin.math.max

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
