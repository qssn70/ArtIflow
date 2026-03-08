# ArtI flow

<div align="center">
  <img src="docs/assets/logo.png" alt="ArtI flow logo" width="108" />

  <h3>一个面向中学学习场景的 Android AI 辅导应用</h3>
  <p>支持拍照搜题、LaTeX 公式渲染、语音追问、学习教练、题目归档与复习辅助。</p>

  <p>
    <img src="https://img.shields.io/badge/Platform-Android-3DDC84?logo=android&logoColor=white" alt="Android" />
    <img src="https://img.shields.io/badge/Language-Kotlin-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin" />
    <img src="https://img.shields.io/badge/UI-Jetpack%20Compose-4285F4?logo=jetpackcompose&logoColor=white" alt="Jetpack Compose" />
    <img src="https://img.shields.io/badge/Min%20SDK-26-orange" alt="Min SDK 26" />
    <img src="https://img.shields.io/badge/Target%20SDK-35-blue" alt="Target SDK 35" />
  </p>
</div>

<p align="center">
  <img src="docs/assets/cover.png" alt="ArtI flow cover" />
</p>

## 🎬 Demo

<p align="center">
  <img src="docs/assets/demo.gif" alt="ArtI flow demo" width="960" />
</p>

<p align="center">
  从图片问答，到公式解释，再到学习教练与手动归档，整个学习流程尽量保持自然、清晰、可复盘。
</p>

## 📸 Screenshots

<p align="center">
  <img src="docs/assets/screenshot-chat.png" alt="聊天问答界面" width="31%" />
  <img src="docs/assets/screenshot-coach.png" alt="学习教练界面" width="31%" />
  <img src="docs/assets/screenshot-history.png" alt="归档与历史界面" width="31%" />
</p>

<p align="center">
  图片问答 · 教练建议 · 题目归档
</p>

---

## ✨ 项目亮点

- **拍照搜题 / 相册搜题**：支持直接上传题目图片，并保留原图质量
- **Markdown + LaTeX 渲染**：适合数学、物理公式展示，兼顾列表与块公式排版
- **语音追问**：支持语音录入与追问式学习交互
- **学习教练模式**：给出复盘建议、训练题、针对性 follow-up
- **题目归档**：可手动收藏题目，沉淀题干、答案、标签与分析摘要
- **Anki 辅助**：支持面向记忆卡片的学习延展能力
- **FlowStudy 对接**：支持配对与上传主界面内容

---

## 🧠 适合什么场景

ArtI flow 更偏向「**学中用**」而不是单纯聊天：

- 做题时直接拍照提问
- 看不懂某一步时继续追问
- 需要公式正常显示而不是纯文本堆叠
- 想把好题、易错题沉淀下来反复看
- 想让 AI 像一个学习教练，而不是只给答案

---

## 🧩 主要功能

| 模块 | 说明 |
| --- | --- |
| 图片问答 | 拍照 / 相册导入题目图片，支持多图场景 |
| 公式显示 | 渲染行内公式、块公式、列表中的公式内容 |
| 语音输入 | 录音后转写，用于追问或继续提问 |
| 教练模式 | 根据最近内容生成建议、练习方向和提示 |
| 题目归档 | 手动收藏整题，保留题干、答案、知识点标签 |
| 学习沉淀 | 支持导出、复盘、错题整理与卡片延展 |

---

## 🛠 技术栈

- **Kotlin**
- **Jetpack Compose**
- **Material 3**
- **OkHttp**
- **Markwon**
- **JLatexMath**
- **Gradle Kotlin DSL**

---

## 🚀 快速开始

### 1) 克隆仓库

```bash
git clone https://github.com/vimalinx/ArtIflow.git
cd ArtIflow
```

### 2) 配置本地参数

先复制配置模板：

```bash
cp local.properties.example local.properties
```

然后按需填写：

- `ARK_API_KEY`
- `ARK_MODEL`
- `ARK_BASE_URL`
- `ARK_ENDPOINT`
- `OPENSPEECH_API_KEY`
- `OPENSPEECH_RESOURCE_ID`
- `FLOWSTUDY_SERVER_URL`（可选）

> `local.properties` 不要提交到仓库。

### 3) 运行项目

如果你使用 Android Studio：

- 直接打开项目根目录
- 等待 Gradle Sync
- 运行 `app` 模块即可

也可以命令行安装调试包：

```bash
./gradlew :app:installDebug
```

---

## 🔧 常用命令

### 编译

```bash
./gradlew :app:compileDebugKotlin
```

### 单元测试

```bash
./gradlew :app:testDebugUnitTest
```

### 安装到设备

```bash
./gradlew :app:installDebug
```

### 无线调试设备示例

```bash
adb connect <手机IP:端口>
adb devices -l
./gradlew :app:installDebug
```

---

## 📁 项目结构

```text
ArtIflow/
├── app/
│   ├── src/main/java/com/studysuit/aiqa/
│   │   ├── data/        # API 客户端、导出、标签分析等
│   │   ├── ui/          # Compose UI、聊天、教练、归档、Markdown/LaTeX
│   │   └── MainActivity.kt
│   ├── src/main/res/
│   └── build.gradle.kts
├── docs/
│   └── assets/          # README 展示图、封面、GIF、logo
├── gradle/
├── local.properties.example
└── README.md
```

---

## 🔐 配置说明

项目默认读取 `local.properties` 中的运行参数，并在构建时注入到 `BuildConfig`。

示例配置文件见：`local.properties.example`

建议：

- 公开仓库只保留模板，不提交真实密钥
- 自建模型地址优先使用测试 key，不要把生产 key 放进公开历史
- 如果切换无线调试设备，优先重新获取新的 `adb connect` 端口

---

## 🧪 当前开发关注点

- 图片问答体验优化
- 公式与 Markdown 混排稳定性
- 教练模式的推荐与训练闭环
- 归档、复习与学习沉淀体验

---

## 🤝 贡献方式

如果你想继续完善这个项目，可以从这些方向入手：

- UI 打磨与交互细节优化
- 图片问答与多图理解体验
- 公式渲染边界场景补测试
- 教练模式提示词与训练流程优化
- Demo、截图、使用文档持续补强

---

## 📌 说明

这是一个持续迭代中的学习型 Android 项目，适合继续扩展为：

- 更完整的错题本
- AI 学习教练
- 题目归档与检索系统
- Anki / 导出 / 复盘一体化工具

如果这个项目对你有帮助，欢迎点个 ⭐。
