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
  fun normalizeLatexForMarkwon_keepsEscapedCurrencyTextUnchanged() {
    val markdown = "价格是 \\$100，不是公式"

    val normalized = normalizeLatexForMarkwon(markdown)

    assertEquals(markdown, normalized)
  }
}
