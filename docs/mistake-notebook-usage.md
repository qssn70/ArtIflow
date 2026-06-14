# ArtIflow 错题本使用与实现说明

## 能力范围

错题本已作为独立工作区接入应用，支持从拍照、相册和题目归档生成错题，结合 OCR 与多模态模型生成可编辑草稿，并按动态 SRS 规则安排复习提醒。

已覆盖的主流程：

- 拍照录入错题：在错题本工作区点击“拍照录入”，保存图片引用并生成识别草稿。
- 相册录入错题：支持一次选择多张图片，统一识别为同一道错题草稿。
- OCR + 多模态识别：先尝试本地 ML Kit OCR，再调用 Ark 多模态模型校对题干、答案、解析、知识点和错因。
- 手动确认草稿：题干和正确答案齐全后进入复习队列，缺失时保留在“待完善”。
- 按记忆曲线复习：答错 10 分钟后复习；首次答对约 1 天后；第二次答对约 7 天后；后续按 ease factor 延长。
- 完成判定：连续 3 次独立作答正确，且最后一次与上次复习间隔不少于 7 天，自动标记为已完成。
- 到期提醒：使用 WorkManager 调度最早到期错题，并在 Android 13+ 申请通知权限。
- 模型判题建议：复习时可输入文字答案，也可拍照/相册上传本次作答图片，模型给出做对/做错建议，最终记录以用户确认按钮为准。
- Anki 延展：错题卡片和复习卡可生成 Anki 卡片，复用现有卡组归类、排序和去重逻辑。
- 筛选统计：支持按页签、搜索、科目、知识点和错误类型筛选，并展示总数、待复习、已完成、近期正确率和薄弱标签。

## 数据与存储

- 错题元数据保存在 `mistake_book_v1.json`。
- 错题图片保存在 app 私有目录下的 `mistake_images/`。
- JSON 只保存图片相对引用，避免把图片 Base64 写进会话文件。
- 题目归档转换错题时，会复制归档中的图片预览到错题图片目录。

## 复习记录

每次复习都会写入 `MistakeReviewAttempt`，记录：

- 复习时间
- 用户本次作答
- 是否做对
- 判定来源：用户手动、模型、用户确认模型建议
- 模型建议摘要
- 备注或图片 OCR 文本

用户直接点击“做对/做错”时，来源为 `USER`。使用模型判题后，只有点击“确认做对/确认做错”才会写入记录，来源为 `USER_CONFIRMED_MODEL`。

## 已知限制

- WorkManager 提醒受系统省电策略影响，不保证分钟级精确。
- 模型判题建议依赖 Ark 配置，模型不可用时仍可手动记录复习结果。
- 图片作答会先 OCR；同时将原图提交给多模态模型，但复杂公式和图表仍建议用户最终确认。
- v1 沿用 JSON 文件存储，暂未迁移到 Room。

## 相关验证

核心测试覆盖：

- `MistakeSrsEngineTest`
- `MistakeBookStorageTest`
- `MistakeRecognitionCoordinatorTest`
- `MistakeReviewSchedulerTest`
- `MistakeBookUiSupportTest`
- `MistakeBookStateReducersTest`
- `StudyChatViewModelMistakeBookTest`
- `MistakeAnswerJudgementTest`

常用验证命令：

```powershell
.\gradlew.bat :app:testDebugUnitTest --no-daemon
.\gradlew.bat :app:compileDebugKotlin --no-daemon
```
