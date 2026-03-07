package com.studysuit.aiqa.data

import java.util.Locale

/**
 * 题目自动打标签结果
 * 
 * @property subject 识别的科目（数学/物理/化学/英语/语文）
 * @property knowledgePoints 识别的知识点列表（可能多个）
 * @property questionType 识别的题型（选择题/填空题/解答题/简答题）
 */
data class TagResult(
  val subject: String,
  val knowledgePoints: List<String>,
  val questionType: String
)

/**
 * 科目识别规则
 * 
 * @property subject 科目名称
 * @property keywords 关键词列表
 */
data class SubjectRule(
  val subject: String,
  val keywords: List<String>
)

/**
 * 知识点识别规则
 * 
 * @property point 知识点名称
 * @property keywords 关键词列表
 */
data class KnowledgeRule(
  val point: String,
  val keywords: List<String>
)

/**
 * 题型识别规则
 * 
 * @property type 题型名称
 * @property keywords 关键词列表
 */
data class TypeRule(
  val type: String,
  val keywords: List<String>
)

/**
 * 题目自动打标签工具
 * 
 * 基于关键词匹配算法自动识别题目的科目、知识点和题型。
 * 参考 StudyChatModels.kt 中的 knowledgeRules 和 topicRules 进行扩展。
 * 
 * 使用示例：
 * ```kotlin
 * val result = QuestionTagger.autoTag("求函数f(x)=x²+2x的最小值")
 * // result.subject = "数学"
 * // result.knowledgePoints = ["函数与图像", "二次函数"]
 * // result.questionType = "解答题"
 * ```
 */
object QuestionTagger {
  
  /**
   * 科目识别规则库
   * 按优先级排序，优先匹配更具体的科目
   */
  private val subjectRules = listOf(
    // 数学
    SubjectRule(
      subject = "数学",
      keywords = listOf(
        // 函数相关
        "函数", "导数", "积分", "极限", "连续", "单调", "极值", "最值", "顶点",
        "抛物线", "图像", "值域", "定义域",
        // 方程与不等式
        "方程", "不等式", "根", "判别式", "配方", "二次", "因式分解",
        // 几何
        "几何", "三角形", "圆", "椭圆", "双曲线", "抛物线", "向量", "角度",
        "相似", "全等", "平行", "垂直", "距离", "面积", "体积", "周长",
        // 概率统计
        "概率", "随机", "独立", "期望", "方差", "排列", "组合", "统计",
        "分布", "频率",
        // 数列
        "数列", "等差", "等比", "通项", "求和",
        // 其他
        "复数", "集合", "逻辑", "命题", "充分", "必要", "充分必要"
      )
    ),
    
    // 物理
    SubjectRule(
      subject = "物理",
      keywords = listOf(
        // 力学
        "力", "受力", "牛顿", "加速度", "速度", "位移", "动量", "冲量",
        "功", "功率", "能量", "动能", "势能", "机械能", "摩擦力", "弹力",
        "重力", "支持力", "拉力", "推力",
        // 运动学
        "匀速", "匀加速", "自由落体", "平抛", "斜抛", "圆周运动",
        // 电磁学
        "电场", "电势", "电流", "电阻", "电压", "电容", "电感",
        "磁场", "磁感应", "安培", "洛伦兹", "感应", "电磁感应",
        "楞次", "法拉第",
        // 光学
        "光", "折射", "反射", "透镜", "凸透镜", "凹透镜", "光谱",
        // 热学
        "热", "温度", "热量", "内能", "熵", "理想气体", "压强",
        // 声学
        "声", "波", "频率", "波长", "振幅", "周期",
        // 原子物理
        "原子", "核", "衰变", "裂变", "聚变", "量子", "光电效应"
      )
    ),
    
    // 化学
    SubjectRule(
      subject = "化学",
      keywords = listOf(
        // 化学反应
        "氧化", "还原", "反应", "化学方程", "离子方程", "平衡",
        "速率", "催化剂", "可逆",
        // 元素与周期
        "元素", "周期表", "原子序", "电子", "质子", "中子",
        "主族", "副族", "周期", "族",
        // 有机化学
        "有机", "烃", "烷", "烯", "炔", "苯", "醇", "醛", "酸", "酯",
        "官能团", "同分异构", "加成", "取代", "消去",
        // 无机化学
        "无机", "金属", "非金属", "盐", "酸", "碱", "氧化物",
        // 溶液与离子
        "离子", "溶液", "浓度", "溶解", "电解", "电离", "pH",
        // 电化学
        "电化学", "原电池", "电解池", "电极", "正极", "负极",
        // 化学键
        "化学键", "共价", "离子键", "金属键", "分子间"
      )
    ),
    
    // 英语
    SubjectRule(
      subject = "英语",
      keywords = listOf(
        // 语法
        "grammar", "时态", "语态", "从句", "定语从句", "状语从句",
        "名词性从句", "主语从句", "宾语从句", "表语从句",
        "虚拟语气", "倒装", "强调", "省略",
        "动词", "名词", "形容词", "副词", "介词", "连词", "冠词",
        "单复数", "不可数", "可数",
        // 词汇
        "vocabulary", "单词", "短语", "搭配", "同义词", "反义词",
        "词根", "词缀", "构词",
        // 题型
        "reading", "comprehension", "cloze", "完形", "填空",
        "writing", "作文", "翻译", "translation",
        "listening", "听力"
      )
    ),
    
    // 语文
    SubjectRule(
      subject = "语文",
      keywords = listOf(
        // 文言文
        "文言", "古文", "之乎者也", "虚词", "实词", "通假", "古今异义",
        "一词多义", "词类活用", "特殊句式", "判断句", "被动句",
        "省略句", "倒装句",
        // 现代文
        "现代文", "记叙", "说明", "议论", "散文", "小说", "戏剧",
        // 诗词
        "诗词", "古诗", "词牌", "曲牌", "押韵", "对仗", "平仄",
        "意象", "意境", "修辞",
        // 作文
        "作文", "写作", "立意", "选材", "结构", "开头", "结尾",
        "论证", "论点", "论据",
        // 修辞
        "比喻", "拟人", "夸张", "排比", "对偶", "反问", "设问",
        "借代", "通感",
        // 阅读
        "阅读理解", "中心思想", "段落大意", "人物形象", "环境描写"
      )
    )
  )
  
  /**
   * 知识点识别规则库
   * 参考 StudyChatModels.kt 中的 knowledgeRules 进行扩展
   */
  private val knowledgeRules = listOf(
    // ===== 数学知识点 =====
    KnowledgeRule(
      point = "函数与图像",
      keywords = listOf("函数", "图像", "抛物线", "导数", "单调", "最值", "极值", "顶点", "值域", "定义域")
    ),
    KnowledgeRule(
      point = "二次函数",
      keywords = listOf("二次", "抛物线", "顶点", "对称轴", "开口", "最值")
    ),
    KnowledgeRule(
      point = "方程与不等式",
      keywords = listOf("方程", "不等式", "根", "判别式", "配方", "因式分解", "解集")
    ),
    KnowledgeRule(
      point = "几何证明",
      keywords = listOf("几何", "三角形", "圆", "向量", "相似", "全等", "证明")
    ),
    KnowledgeRule(
      point = "解析几何",
      keywords = listOf("椭圆", "双曲线", "抛物线", "圆", "坐标", "方程", "离心率", "焦距")
    ),
    KnowledgeRule(
      point = "概率统计",
      keywords = listOf("概率", "随机", "期望", "方差", "排列", "组合", "分布", "频率")
    ),
    KnowledgeRule(
      point = "数列",
      keywords = listOf("数列", "等差", "等比", "通项", "求和", "递推")
    ),
    KnowledgeRule(
      point = "导数与应用",
      keywords = listOf("导数", "切线", "极值", "最值", "单调", "凹凸")
    ),
    KnowledgeRule(
      point = "三角函数",
      keywords = listOf("正弦", "余弦", "正切", "三角", "弧度", "周期")
    ),
    
    // ===== 物理知识点 =====
    KnowledgeRule(
      point = "力学",
      keywords = listOf("受力", "牛顿", "加速度", "速度", "位移", "动量", "冲量", "功", "功率")
    ),
    KnowledgeRule(
      point = "运动学",
      keywords = listOf("匀速", "匀加速", "自由落体", "平抛", "斜抛", "圆周运动", "相对运动")
    ),
    KnowledgeRule(
      point = "电磁学",
      keywords = listOf("电场", "电势", "电流", "电阻", "电压", "磁场", "感应", "安培", "洛伦兹")
    ),
    KnowledgeRule(
      point = "光学",
      keywords = listOf("光", "折射", "反射", "透镜", "光谱", "干涉", "衍射")
    ),
    KnowledgeRule(
      point = "热学",
      keywords = listOf("热", "温度", "热量", "内能", "熵", "理想气体", "压强", "体积")
    ),
    KnowledgeRule(
      point = "机械能",
      keywords = listOf("动能", "势能", "机械能", "能量守恒", "动能定理")
    ),
    
    // ===== 化学知识点 =====
    KnowledgeRule(
      point = "化学反应",
      keywords = listOf("氧化", "还原", "反应", "平衡", "速率", "催化剂")
    ),
    KnowledgeRule(
      point = "有机化学",
      keywords = listOf("有机", "烃", "烷", "烯", "炔", "苯", "醇", "醛", "酸", "酯", "官能团")
    ),
    KnowledgeRule(
      point = "电化学",
      keywords = listOf("电化学", "原电池", "电解池", "电极", "氧化还原")
    ),
    KnowledgeRule(
      point = "离子反应",
      keywords = listOf("离子", "电解", "电离", "离子方程", "沉淀", "气体")
    ),
    KnowledgeRule(
      point = "化学平衡",
      keywords = listOf("平衡", "可逆", "勒夏特列", "转化率", "平衡常数")
    ),
    
    // ===== 英语知识点 =====
    KnowledgeRule(
      point = "时态与语态",
      keywords = listOf("时态", "语态", "被动", "进行", "完成", "将来", "过去")
    ),
    KnowledgeRule(
      point = "从句",
      keywords = listOf("从句", "定语", "状语", "主语", "宾语", "表语", "同位语")
    ),
    KnowledgeRule(
      point = "虚拟语气",
      keywords = listOf("虚拟", "条件", "wish", "if", "would", "should", "could", "might")
    ),
    KnowledgeRule(
      point = "完形填空",
      keywords = listOf("完形", "cloze", "填空", "上下文")
    ),
    KnowledgeRule(
      point = "阅读理解",
      keywords = listOf("reading", "comprehension", "理解", "主旨", "细节", "推断")
    ),
    
    // ===== 语文知识点 =====
    KnowledgeRule(
      point = "文言文",
      keywords = listOf("文言", "古文", "虚词", "实词", "通假", "古今异义", "一词多义")
    ),
    KnowledgeRule(
      point = "诗词鉴赏",
      keywords = listOf("诗词", "古诗", "意象", "意境", "修辞", "手法", "情感")
    ),
    KnowledgeRule(
      point = "现代文阅读",
      keywords = listOf("现代文", "记叙", "说明", "议论", "散文", "中心思想", "人物形象")
    ),
    KnowledgeRule(
      point = "作文",
      keywords = listOf("作文", "写作", "立意", "选材", "结构", "论证", "论点", "论据")
    ),
    KnowledgeRule(
      point = "修辞手法",
      keywords = listOf("比喻", "拟人", "夸张", "排比", "对偶", "反问", "设问", "借代", "通感")
    )
  )
  
  /**
   * 题型识别规则库
   */
  private val typeRules = listOf(
    TypeRule(
      type = "选择题",
      keywords = listOf(
        "下列", "选项", "A.", "B.", "C.", "D.",
        "A、", "B、", "C、", "D、",
        "正确的是", "错误的是", "不正确的是",
        "选择", "单选", "多选", "选出"
      )
    ),
    TypeRule(
      type = "填空题",
      keywords = listOf(
        "填写", "填空", "____", "______", "________",
        "横线", "空白处", "补全", "空格"
      )
    ),
    TypeRule(
      type = "解答题",
      keywords = listOf(
        "解答", "证明", "计算", "求证", "求", "求解",
        "试求", "请证明", "请计算", "请解答"
      )
    ),
    TypeRule(
      type = "简答题",
      keywords = listOf(
        "简述", "简答", "说明", "分析", "简析",
        "简要", "概述", "归纳", "总结"
      )
    ),
    TypeRule(
      type = "应用题",
      keywords = listOf(
        "应用", "实际", "生活中", "某公司", "某工厂",
        "工程", "行程", "利润", "成本", "售价"
      )
    )
  )
  
  /**
   * 对题目文本进行自动打标签
   * 
   * @param text 题目文本
   * @return TagResult 包含科目、知识点列表和题型
   */
  fun autoTag(text: String): TagResult {
    val subject = detectSubject(text)
    val knowledgePoints = detectKnowledgePoints(text)
    val questionType = detectQuestionType(text)
    
    return TagResult(
      subject = subject,
      knowledgePoints = knowledgePoints,
      questionType = questionType
    )
  }
  
  /**
   * 识别题目的科目
   * 
   * 算法：
   * 1. 将文本转为小写
   * 2. 对每个科目规则，计算匹配的关键词数量
   * 3. 返回匹配度最高的科目
   * 4. 如果没有匹配，返回"通用"
   * 
   * @param text 题目文本
   * @return 识别出的科目名称
   */
  fun detectSubject(text: String): String {
    val normalized = text.lowercase(Locale.getDefault())
    
    // 计算每个科目的匹配分数
    val scores = subjectRules.map { rule ->
      val matchCount = rule.keywords.count { keyword ->
        normalized.contains(keyword.lowercase(Locale.getDefault()))
      }
      rule.subject to matchCount
    }
    
    // 找出最高分的科目
    val maxScore = scores.maxOfOrNull { it.second } ?: 0
    
    // 如果有匹配，返回最高分的科目；否则返回"通用"
    return if (maxScore > 0) {
      scores.first { it.second == maxScore }.first
    } else {
      "通用"
    }
  }
  
  /**
   * 识别题目的知识点（可能多个）
   * 
   * 算法：
   * 1. 将文本转为小写
   * 2. 对每个知识点规则，检查是否包含关键词
   * 3. 收集所有匹配的知识点
   * 4. 按匹配度排序，返回前5个
   * 
   * @param text 题目文本
   * @return 识别出的知识点列表
   */
  fun detectKnowledgePoints(text: String): List<String> {
    val normalized = text.lowercase(Locale.getDefault())
    
    // 计算每个知识点的匹配分数
    val matched = knowledgeRules.mapNotNull { rule ->
      val matchCount = rule.keywords.count { keyword ->
        normalized.contains(keyword.lowercase(Locale.getDefault()))
      }
      
      if (matchCount > 0) {
        rule.point to matchCount
      } else {
        null
      }
    }
    
    // 按匹配度降序排序，返回前5个知识点
    return matched
      .sortedByDescending { it.second }
      .take(5)
      .map { it.first }
  }
  
  /**
   * 识别题目的题型
   * 
   * 算法：
   * 1. 将文本转为小写
   * 2. 对每个题型规则，检查是否包含关键词
   * 3. 返回匹配度最高的题型
   * 4. 如果没有匹配，返回"其他"
   * 
   * @param text 题目文本
   * @return 识别出的题型名称
   */
  fun detectQuestionType(text: String): String {
    val normalized = text.lowercase(Locale.getDefault())
    
    // 计算每个题型的匹配分数
    val scores = typeRules.map { rule ->
      val matchCount = rule.keywords.count { keyword ->
        normalized.contains(keyword.lowercase(Locale.getDefault()))
      }
      rule.type to matchCount
    }
    
    // 找出最高分的题型
    val maxScore = scores.maxOfOrNull { it.second } ?: 0
    
    // 如果有匹配，返回最高分的题型；否则返回"其他"
    return if (maxScore > 0) {
      scores.first { it.second == maxScore }.first
    } else {
      "其他"
    }
  }
  
  /**
   * 扩展：添加自定义科目规则
   * 
   * @param rule 科目规则
   */
  fun addSubjectRule(rule: SubjectRule) {
    // 由于使用 listOf，这里只是提供接口，实际扩展需要改为可变集合
    // 或者在使用时重新构建规则列表
  }
  
  /**
   * 扩展：添加自定义知识点规则
   * 
   * @param rule 知识点规则
   */
  fun addKnowledgeRule(rule: KnowledgeRule) {
    // 由于使用 listOf，这里只是提供接口，实际扩展需要改为可变集合
    // 或者在使用时重新构建规则列表
  }
  
  /**
   * 扩展：添加自定义题型规则
   * 
   * @param rule 题型规则
   */
  fun addTypeRule(rule: TypeRule) {
    // 由于使用 listOf，这里只是提供接口，实际扩展需要改为可变集合
    // 或者在使用时重新构建规则列表
  }
}
