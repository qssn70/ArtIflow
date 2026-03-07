package com.studysuit.aiqa.data

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 导出结果密封类
 */
sealed class ExportResult {
  data class Success(val file: File, val message: String) : ExportResult()
  data class Error(val exception: Exception, val message: String) : ExportResult()
}

/**
 * 错题导出器
 * 支持多种格式的错题导出：Markdown、CSV、Anki、PDF
 */
class MistakeExporter(private val context: Context) {

  private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
  private val fileDateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())

  /**
   * 主导出方法
   *
   * @param mistakes 错题列表
   * @param format 导出格式
   * @param filename 自定义文件名（可选）
   * @return 导出结果
   */
  suspend fun export(
    mistakes: List<MistakeQuestion>,
    format: ExportFormat,
    filename: String? = null
  ): ExportResult {
    if (mistakes.isEmpty()) {
      return ExportResult.Error(
        IllegalArgumentException("错题列表为空"),
        "没有可导出的错题"
      )
    }

    return withContext(Dispatchers.IO) {
      try {
        val timestamp = fileDateFormat.format(Date())
        val defaultFilename = "错题导出_$timestamp"
        val actualFilename = filename ?: defaultFilename

        when (format) {
          ExportFormat.MARKDOWN -> exportToMarkdown(mistakes, actualFilename)
          ExportFormat.JSON -> exportToCSV(mistakes, actualFilename) // JSON 格式在 MistakeModels 中已定义，这里使用 CSV
          ExportFormat.EXCEL -> exportToCSV(mistakes, actualFilename)
          ExportFormat.PDF -> exportToPDF(mistakes, actualFilename)
        }
      } catch (e: Exception) {
        ExportResult.Error(e, "导出失败: ${e.message}")
      }
    }
  }

  /**
   * 导出为 Markdown 格式
   */
  private fun exportToMarkdown(
    mistakes: List<MistakeQuestion>,
    filename: String
  ): ExportResult {
    try {
      val file = createExportFile(filename, "md")
      val content = buildMarkdownContent(mistakes)

      file.writeText(content, charset = Charsets.UTF_8)

      return ExportResult.Success(
        file,
        "成功导出 ${mistakes.size} 道错题到 Markdown 文件"
      )
    } catch (e: Exception) {
      return ExportResult.Error(e, "Markdown 导出失败: ${e.message}")
    }
  }

  /**
   * 导出为 CSV 格式（带表头）
   */
  private fun exportToCSV(
    mistakes: List<MistakeQuestion>,
    filename: String
  ): ExportResult {
    try {
      val file = createExportFile(filename, "csv")
      val content = buildCSVContent(mistakes)

      file.writeText(content, charset = Charsets.UTF_8)

      return ExportResult.Success(
        file,
        "成功导出 ${mistakes.size} 道错题到 CSV 文件"
      )
    } catch (e: Exception) {
      return ExportResult.Error(e, "CSV 导出失败: ${e.message}")
    }
  }

  /**
   * 导出为 Anki 卡片格式
   */
  suspend fun exportToAnki(
    mistakes: List<MistakeQuestion>,
    deckName: String = "错题集"
  ): ExportResult {
    return withContext(Dispatchers.IO) {
      try {
        val timestamp = fileDateFormat.format(Date())
        val filename = "Anki_${deckName}_$timestamp"
        val file = createExportFile(filename, "txt")
        val content = buildAnkiContent(mistakes, deckName)

        file.writeText(content, charset = Charsets.UTF_8)

        ExportResult.Success(
          file,
          "成功导出 ${mistakes.size} 道错题为 Anki 卡片"
        )
      } catch (e: Exception) {
        ExportResult.Error(e, "Anki 导出失败: ${e.message}")
      }
    }
  }

  /**
   * 导出为 PDF（使用 Android PdfDocument）
   */
  private fun exportToPDF(
    mistakes: List<MistakeQuestion>,
    filename: String
  ): ExportResult {
    try {
      val file = createExportFile(filename, "pdf")
      val document = PdfDocument()

      // A4 页面尺寸 (595 x 842 points)
      val pageWidth = 595
      val pageHeight = 842
      val margin = 40f
      var currentPageNumber = 1

      // 创建画笔
      val titlePaint = Paint().apply {
        textSize = 20f
        isFakeBoldText = true
        color = android.graphics.Color.BLACK
      }

      val contentPaint = Paint().apply {
        textSize = 12f
        color = android.graphics.Color.BLACK
      }

      val headerPaint = Paint().apply {
        textSize = 14f
        isFakeBoldText = true
        color = android.graphics.Color.DKGRAY
      }

      var currentPage = document.startPage(
        PdfDocument.PageInfo.Builder(pageWidth, pageHeight, currentPageNumber).create()
      )
      var canvas = currentPage.canvas
      var yPosition = margin

      // 绘制标题
      yPosition = drawMultilineText(
        canvas,
        "错题导出报告",
        margin,
        yPosition,
        pageWidth - 2 * margin,
        titlePaint
      )
      yPosition += 20f

      // 绘制导出时间
      yPosition = drawMultilineText(
        canvas,
        "导出时间: ${dateFormat.format(Date())}",
        margin,
        yPosition,
        pageWidth - 2 * margin,
        contentPaint
      )
      yPosition += 20f

      // 绘制错题数量
      yPosition = drawMultilineText(
        canvas,
        "错题总数: ${mistakes.size}",
        margin,
        yPosition,
        pageWidth - 2 * margin,
        contentPaint
      )
      yPosition += 30f

      // 遍历每道错题
      mistakes.forEachIndexed { index, mistake ->
        // 检查是否需要新页面
        if (yPosition > pageHeight - 100) {
          // 绘制页码
          canvas.drawText(
            "第 $currentPageNumber 页",
            pageWidth / 2f - 30f,
            pageHeight - 20f,
            contentPaint
          )

          document.finishPage(currentPage)
          currentPageNumber++
          currentPage = document.startPage(
            PdfDocument.PageInfo.Builder(pageWidth, pageHeight, currentPageNumber).create()
          )
          canvas = currentPage.canvas
          yPosition = margin
        }

        // 绘制题号和类型
        val typeLabel = getMistakeTypeLabel(mistake.mistakeType)
        yPosition = drawMultilineText(
          canvas,
          "【第 ${index + 1} 题】$typeLabel - ${mistake.subject}",
          margin,
          yPosition,
          pageWidth - 2 * margin,
          headerPaint
        )
        yPosition += 10f

        // 绘制题目
        yPosition = drawMultilineText(
          canvas,
          "题目: ${mistake.questionText}",
          margin + 10f,
          yPosition,
          pageWidth - 2 * margin - 10f,
          contentPaint
        )
        yPosition += 10f

        // 绘制学生答案
        yPosition = drawMultilineText(
          canvas,
          "学生答案: ${mistake.studentAnswer}",
          margin + 10f,
          yPosition,
          pageWidth - 2 * margin - 10f,
          contentPaint
        )
        yPosition += 10f

        // 绘制正确答案
        yPosition = drawMultilineText(
          canvas,
          "正确答案: ${mistake.correctAnswer}",
          margin + 10f,
          yPosition,
          pageWidth - 2 * margin - 10f,
          contentPaint
        )
        yPosition += 10f

        // 绘制错误原因
        if (mistake.mistakeReason.isNotBlank()) {
          yPosition = drawMultilineText(
            canvas,
            "错误原因: ${mistake.mistakeReason}",
            margin + 10f,
            yPosition,
            pageWidth - 2 * margin - 10f,
            contentPaint
          )
          yPosition += 10f
        }

        // AI 分析
        mistake.aiAnalysis?.let { analysis ->
          yPosition = drawMultilineText(
            canvas,
            "分析: ${analysis.rootCause}",
            margin + 10f,
            yPosition,
            pageWidth - 2 * margin - 10f,
            contentPaint
          )
          yPosition += 10f

          if (analysis.suggestions.isNotEmpty()) {
            yPosition = drawMultilineText(
              canvas,
              "建议: ${analysis.suggestions.joinToString("; ")}",
              margin + 10f,
              yPosition,
              pageWidth - 2 * margin - 10f,
              contentPaint
            )
            yPosition += 10f
          }
        }

        yPosition += 20f // 题目之间的间距
      }

      // 绘制最后一页的页码
      canvas.drawText(
        "第 $currentPageNumber 页",
        pageWidth / 2f - 30f,
        pageHeight - 20f,
        contentPaint
      )

      document.finishPage(currentPage)

      // 保存到文件
      FileOutputStream(file).use { outputStream ->
        document.writeTo(outputStream)
      }

      document.close()

      return ExportResult.Success(
        file,
        "成功导出 ${mistakes.size} 道错题到 PDF 文件"
      )
    } catch (e: Exception) {
      return ExportResult.Error(e, "PDF 导出失败: ${e.message}")
    }
  }

  /**
   * 创建导出文件
   *
   * @param filename 文件名（不含扩展名）
   * @param extension 文件扩展名
   * @return 创建的文件
   */
  private fun createExportFile(filename: String, extension: String): File {
    val downloadsDir = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      // Android 10+ 使用 MediaStore
      File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "ArtIflow/错题导出")
    } else {
      // Android 9 及以下使用传统文件系统
      File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "ArtIflow/错题导出")
    }

    // 确保目录存在
    if (!downloadsDir.exists()) {
      downloadsDir.mkdirs()
    }

    // 创建文件（如果文件名冲突，自动添加序号）
    var file = File(downloadsDir, "$filename.$extension")
    var counter = 1
    while (file.exists()) {
      file = File(downloadsDir, "${filename}_$counter.$extension")
      counter++
    }

    file.createNewFile()
    return file
  }

  /**
   * CSV 转义
   * 处理包含逗号、引号、换行符的文本
   */
  private fun escapeCSV(text: String): String {
    if (text.contains(",") || text.contains("\"") || text.contains("\n") || text.contains("\r")) {
      return "\"${text.replace("\"", "\"\"")}\""
    }
    return text
  }

  /**
   * 绘制多行文本
   *
   * @param canvas 画布
   * @param text 文本内容
   * @param x 起始 X 坐标
   * @param y 起始 Y 坐标
   * @param maxWidth 最大宽度
   * @param paint 画笔
   * @return 新的 Y 坐标（绘制后的位置）
   */
  private fun drawMultilineText(
    canvas: Canvas,
    text: String,
    x: Float,
    y: Float,
    maxWidth: Float,
    paint: Paint
  ): Float {
    var currentY = y
    val lines = text.split("\n")

    lines.forEach { line ->
      // 动态换行
      var remainingText = line
      while (remainingText.isNotEmpty()) {
        val measuredWidth = paint.measureText(remainingText)
        if (measuredWidth <= maxWidth) {
          canvas.drawText(remainingText, x, currentY, paint)
          currentY += paint.textSize + 4f
          break
        } else {
          // 找到能放下的最大字符数
          var end = remainingText.length
          while (paint.measureText(remainingText.substring(0, end)) > maxWidth && end > 0) {
            end--
          }

          if (end > 0) {
            canvas.drawText(remainingText.substring(0, end), x, currentY, paint)
            currentY += paint.textSize + 4f
            remainingText = remainingText.substring(end)
          } else {
            break
          }
        }
      }
    }

    return currentY
  }

  /**
   * 获取错题类型标签
   */
  private fun getMistakeTypeLabel(type: MistakeType): String {
    return when (type) {
      MistakeType.CONCEPT_ERROR -> "概念错误"
      MistakeType.CALCULATION_ERROR -> "计算错误"
      MistakeType.READING_ERROR -> "审题错误"
      MistakeType.METHOD_ERROR -> "方法错误"
    }
  }

  /**
   * 构建 Markdown 内容
   */
  private fun buildMarkdownContent(mistakes: List<MistakeQuestion>): String {
    val builder = StringBuilder()

    builder.appendLine("# 错题导出报告")
    builder.appendLine()
    builder.appendLine("**导出时间**: ${dateFormat.format(Date())}")
    builder.appendLine("**错题总数**: ${mistakes.size}")
    builder.appendLine()

    mistakes.forEachIndexed { index, mistake ->
      builder.appendLine("## 第 ${index + 1} 题")
      builder.appendLine()
      builder.appendLine("**类型**: ${getMistakeTypeLabel(mistake.mistakeType)}")
      builder.appendLine("**科目**: ${mistake.subject}")
      builder.appendLine("**题型**: ${mistake.questionType}")
      builder.appendLine()

      builder.appendLine("### 题目")
      builder.appendLine(mistake.questionText)
      builder.appendLine()

      builder.appendLine("### 学生答案")
      builder.appendLine(mistake.studentAnswer)
      builder.appendLine()

      builder.appendLine("### 正确答案")
      builder.appendLine(mistake.correctAnswer)
      builder.appendLine()

      if (mistake.mistakeReason.isNotBlank()) {
        builder.appendLine("### 错误原因")
        builder.appendLine(mistake.mistakeReason)
        builder.appendLine()
      }

      mistake.aiAnalysis?.let { analysis ->
        builder.appendLine("### AI 分析")
        builder.appendLine("**根本原因**: ${analysis.rootCause}")
        builder.appendLine()

        if (analysis.suggestions.isNotEmpty()) {
          builder.appendLine("**改进建议**:")
          analysis.suggestions.forEach { suggestion ->
            builder.appendLine("- $suggestion")
          }
          builder.appendLine()
        }

        if (analysis.relatedConcepts.isNotEmpty()) {
          builder.appendLine("**相关知识点**: ${analysis.relatedConcepts.joinToString(", ")}")
          builder.appendLine()
        }
      }

      builder.appendLine("---")
      builder.appendLine()
    }

    return builder.toString()
  }

  /**
   * 构建 CSV 内容（带表头）
   */
  private fun buildCSVContent(mistakes: List<MistakeQuestion>): String {
    val builder = StringBuilder()

    // CSV 表头
    builder.appendLine(
      "题号,科目,题型,错误类型,题目,学生答案,正确答案,错误原因,知识点,掌握程度,复习次数,创建时间"
    )

    // CSV 数据行
    mistakes.forEachIndexed { index, mistake ->
      builder.appendLine(
        listOf(
          index + 1,
          escapeCSV(mistake.subject),
          escapeCSV(mistake.questionType),
          escapeCSV(getMistakeTypeLabel(mistake.mistakeType)),
          escapeCSV(mistake.questionText),
          escapeCSV(mistake.studentAnswer),
          escapeCSV(mistake.correctAnswer),
          escapeCSV(mistake.mistakeReason),
          escapeCSV(mistake.knowledgePoints.joinToString("; ")),
          mistake.masteryLevel,
          mistake.reviewCount,
          dateFormat.format(Date(mistake.createdAt))
        ).joinToString(",")
      )
    }

    return builder.toString()
  }

  /**
   * 构建 Anki 卡片内容
   */
  private fun buildAnkiContent(mistakes: List<MistakeQuestion>, deckName: String): String {
    val builder = StringBuilder()

    mistakes.forEach { mistake ->
      // Anki 卡片格式：front|back|tags
      val front = buildAnkiFront(mistake)
      val back = buildAnkiBack(mistake)
      val tags = buildAnkiTags(mistake)

      builder.appendLine("$front\t$back\t$tags")
    }

    return builder.toString()
  }

  /**
   * 构建 Anki 卡片正面
   */
  private fun buildAnkiFront(mistake: MistakeQuestion): String {
    val builder = StringBuilder()
    builder.append("【${mistake.subject}】${mistake.questionType}")
    builder.append("\n\n")
    builder.append(mistake.questionText)
    return escapeCSV(builder.toString())
  }

  /**
   * 构建 Anki 卡片背面
   */
  private fun buildAnkiBack(mistake: MistakeQuestion): String {
    val builder = StringBuilder()
    builder.append("✅ 正确答案: ${mistake.correctAnswer}")
    builder.append("\n\n")
    builder.append("❌ 你的答案: ${mistake.studentAnswer}")

    if (mistake.mistakeReason.isNotBlank()) {
      builder.append("\n\n")
      builder.append("错误原因: ${mistake.mistakeReason}")
    }

    mistake.aiAnalysis?.let { analysis ->
      builder.append("\n\n")
      builder.append("💡 分析: ${analysis.rootCause}")

      if (analysis.suggestions.isNotEmpty()) {
        builder.append("\n\n")
        builder.append("建议:\n")
        analysis.suggestions.forEach { suggestion ->
          builder.append("- $suggestion\n")
        }
      }
    }

    return escapeCSV(builder.toString())
  }

  /**
   * 构建 Anki 卡片标签
   */
  private fun buildAnkiTags(mistake: MistakeQuestion): String {
    val tags = mutableListOf<String>()
    tags.add(mistake.subject)
    tags.add(getMistakeTypeLabel(mistake.mistakeType))
    tags.addAll(mistake.knowledgePoints)
    return tags.joinToString(" ") { tag -> tag.replace(" ", "_") }
  }
}
