package com.studysuit.aiqa.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StudyChatMarkdownTest {

  @Test
  fun containsLatexMarkdown_detectsSingleDollarInlineFormula() {
    val markdown = "抛物线顶点可以写成 ${'$'}y=a(x-h)^2+k${'$'} 的形式"

    assertTrue(containsLatexMarkdown(markdown))
  }

  @Test
  fun containsLatexMarkdown_detectsDoubleDollarInlineFormula() {
    val markdown = "欧拉公式 ${'$'}${'$'}e^{i\\pi}+1=0${'$'}${'$'} 很经典"

    assertTrue(containsLatexMarkdown(markdown))
  }

  @Test
  fun containsLatexMarkdown_detectsDoubleDollarBlockFormula() {
    val markdown = """
      先看这个式子：

      ${'$'}${'$'}
      x_1 = \\frac{-b + \\sqrt{b^2 - 4ac}}{2a}
      ${'$'}${'$'}
    """.trimIndent()

    assertTrue(containsLatexMarkdown(markdown))
  }

  @Test
  fun containsLatexMarkdown_detectsSlashBracketAndSlashParenForms() {
    val inlineMarkdown = "可写成 \\(x^2 + y^2 = z^2\\)"
    val blockMarkdown = "\\[\\int_0^1 x^2 dx = \\frac{1}{3}\\]"

    assertTrue(containsLatexMarkdown(inlineMarkdown))
    assertTrue(containsLatexMarkdown(blockMarkdown))
  }

  @Test
  fun containsLatexMarkdown_returnsFalseForPlainMarkdown() {
    val markdown = "**结论**：先列已知，再列目标。"

    assertFalse(containsLatexMarkdown(markdown))
  }

  @Test
  fun containsLatexMarkdown_ignoresEscapedCurrencyText() {
    val markdown = "价格是 \\$100，不是公式"

    assertFalse(containsLatexMarkdown(markdown))
  }

  @Test
  fun normalizeLatexForMarkwon_convertsSingleDollarInlineFormula() {
    val markdown = "抛物线顶点可以写成 ${'$'}y=a(x-h)^2+k${'$'} 的形式"

    val normalized = normalizeLatexForMarkwon(markdown)

    assertTrue(normalized.contains("${'$'}${'$'}y=a(x-h)^2+k${'$'}${'$'}"))
  }

  @Test
  fun normalizeLatexForMarkwon_convertsSlashParenAndSlashBracketForms() {
    val markdown = "可写成 \\(x^2 + y^2 = z^2\\)，也可写成 \\[x^2+y^2=z^2\\]"

    val normalized = normalizeLatexForMarkwon(markdown)

    assertTrue(normalized.contains("${'$'}${'$'}x^2 + y^2 = z^2${'$'}${'$'}"))
    assertTrue(normalized.contains("${'$'}${'$'}\nx^2+y^2=z^2\n${'$'}${'$'}"))
  }

  @Test
  fun normalizeLatexForMarkwon_keepsOrderedListTextAroundInlineFormula() {
    val markdown = "1. 由核反应前后**质量数守恒**：\\(235+1=236\\)，而\\(141+92=233\\)，所以 \\(3X\\) 的总质量数是 3。"

    val normalized = normalizeLatexForMarkwon(markdown)

    assertTrue(normalized.startsWith("1. "))
    assertTrue(normalized.contains("${'$'}${'$'}235+1=236${'$'}${'$'}"))
    assertTrue(normalized.contains("${'$'}${'$'}141+92=233${'$'}${'$'}"))
    assertTrue(normalized.contains("${'$'}${'$'}3X${'$'}${'$'}"))
  }

  @Test
  fun normalizeLatexForMarkwon_preservesIndentedBlockFormulaInsideOrderedList() {
    val markdown = """
      2. 双缝干涉公式：
         \[
         \Delta x=\frac{L\lambda}{d}
         \]
      3. 代入数据。
    """.trimIndent()

    val normalized = normalizeLatexForMarkwon(markdown)

    assertTrue(normalized.contains("2. 双缝干涉公式："))
    assertTrue(normalized.contains("\n   ${'$'}${'$'}\n   \\Delta x=\\frac{L\\lambda}{d}\n   ${'$'}${'$'}\n3. 代入数据。"))
  }

  @Test
  fun splitParagraphs_keepsWholeReplyWhenInlineLatexExists() {
    val markdown = "先看结论：当 x=1 时，${'$'}f(x)=x^2+1${'$'}。\n\n再代入中文说明。"

    val spans = splitParagraphs(markdown)

    assertEquals(1, spans.size)
    assertEquals(markdown.trim(), spans.first())
  }

  @Test
  fun splitParagraphs_keepsWholeReplyWhenBlockLatexExists() {
    val markdown = """
      解：

      \[
      x^2 + y^2 = z^2
      \]

      所以直角三角形满足勾股定理。
    """.trimIndent()

    val spans = splitParagraphs(markdown)

    assertEquals(1, spans.size)
    assertEquals(markdown.trim(), spans.first())
  }

  @Test
  fun splitParagraphs_stillSplitsPlainTextWithoutLatex() {
    val markdown = "第一句比较短但是开始铺垫背景。第二句继续补充条件并稍微拉长一点。第三句还是纯文本说明而且继续延长长度避免被并到同一段。第四句继续补充结论。"

    val spans = splitParagraphs(markdown)

    assertTrue(spans.size >= 2)
    assertTrue(spans.all { !containsLatexMarkdown(it) })
  }
}
