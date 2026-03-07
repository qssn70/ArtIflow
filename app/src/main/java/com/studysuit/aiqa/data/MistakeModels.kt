package com.studysuit.aiqa.data

/**
 * 错题类型枚举
 * 用于标识错题的分类
 */
enum class MistakeType {
  /** 概念错误 - 对基本概念理解有误 */
  CONCEPT_ERROR,
  
  /** 计算错误 - 运算过程中的错误 */
  CALCULATION_ERROR,
  
  /** 审题错误 - 理解题目意图时出现偏差 */
  READING_ERROR,
  
  /** 方法错误 - 解题思路或方法选择不当 */
  METHOD_ERROR
}

/**
 * 错题分析数据类
 * AI 对错题的深度分析结果
 */
data class MistakeAnalysis(
  /** 根本原因 - 错误产生的深层原因 */
  val rootCause: String,
  
  /** 改进建议 - 针对性的学习建议列表 */
  val suggestions: List<String>,
  
  /** 相关概念 - 需要复习或加强的知识点 */
  val relatedConcepts: List<String>
)

/**
 * 题目标签数据类
 * 用于分类和筛选题目
 */
data class QuestionTags(
  /** 难度级别 */
  val difficulty: String,
  
  /** 重要性等级 */
  val importance: String,
  
  /** 出现频率 */
  val frequency: String
)

/**
 * 错题数据类
 * 存储单道错题的完整信息
 */
data class MistakeQuestion(
  /** 唯一标识符 */
  val id: String,
  
  /** 题目图片路径（可选） */
  val questionImage: String?,
  
  /** 题目文本内容 */
  val questionText: String,
  
  /** 所属科目 */
  val subject: String,
  
  /** 涉及的知识点列表 */
  val knowledgePoints: List<String>,
  
  /** 题型（如选择题、填空题等） */
  val questionType: String,
  
  /** 错误类型 */
  val mistakeType: MistakeType,
  
  /** 错误原因描述 */
  val mistakeReason: String,
  
  /** 正确答案 */
  val correctAnswer: String,
  
  /** 学生答案 */
  val studentAnswer: String,
  
  /** AI 分析结果（可选） */
  val aiAnalysis: MistakeAnalysis?,
  
  /** 创建时间戳（毫秒） */
  val createdAt: Long,
  
  /** 最后复习时间戳（毫秒，可选） */
  val reviewedAt: Long?,
  
  /** 掌握程度（0-100） */
  val masteryLevel: Int,
  
  /** 复习次数 */
  val reviewCount: Int,
  
  /** 下次复习时间戳（毫秒，可选） */
  val nextReviewAt: Long?
)

/**
 * 分组方式枚举
 * 用于错题列表的分组展示
 */
enum class GroupByType {
  /** 按科目分组 */
  BY_SUBJECT,
  
  /** 按错误类型分组 */
  BY_MISTAKE_TYPE,
  
  /** 按日期分组 */
  BY_DATE,
  
  /** 按掌握程度分组 */
  BY_MASTERY
}

/**
 * 导出格式枚举
 * 支持的错题导出格式
 */
enum class ExportFormat {
  /** PDF 文档 */
  PDF,
  
  /** Excel 表格 */
  EXCEL,
  
  /** Markdown 文档 */
  MARKDOWN,
  
  /** JSON 数据 */
  JSON
}
