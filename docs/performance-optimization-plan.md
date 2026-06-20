# ArtIflow 全局性能优化计划

## Summary

优先做全局性能治理：先建立可重复测量基线，再分批优化 UI 渲染、图片处理、本地存储和发布构建。目标是减少主线程阻塞、长列表一次性组合、图片与 Markdown 重复解码，以及大 JSON 持久化带来的卡顿。

## Key Changes

- 建立性能基线：加入 Macrobenchmark/JankStats，覆盖冷启动、聊天长列表滚动、流式回答、错题本 200 条筛选、含图片/公式消息打开等场景。
- UI 渲染优化：将错题本内部 `verticalScroll + forEach` 改为真正的 `LazyColumn` 分段渲染；聊天、教练、归档列表补齐 `contentType`，拆小重组范围，避免全局 `uiState` 更新带动整页重组。
- Markdown/LaTeX 优化：缓存 Markdown 解析结果和 Markwon 渲染输入；流式回答期间按 50-100ms 合并 UI 刷新，完成后再做完整公式渲染。
- 图片优化：相册/拍照读取后生成缩略图用于 UI，原图仅用于模型请求；图片解码移到 IO/default dispatcher；避免 Compose 组合阶段同步 `BitmapFactory.decode*`。
- 存储优化：把聊天与归档中的 Base64 图片从 `study_suit_sessions_v1.json` 迁移为文件引用，沿用错题本图片引用思路；新增 v2 存储格式并兼容读取 v1；保存动作做 debounce/合并和原子写入。
- 构建优化：Release 开启 R8/minify 与资源压缩，补充 keep 规则；加入 Baseline Profile，优化启动和常用页面切换。

## API / Type Changes

- 新增内部图片引用模型，例如 `StoredImageRef(id, path, mimeType, width, height, thumbnailPath)`，聊天消息和归档题目改为保存引用列表。
- `SessionStorage` 升级为 v2：读取 v1 时自动把 Base64 图片落盘并写回 v2；导出仍支持生成完整可迁移 payload。
- 不改变 Ark/OpenSpeech/FlowStudy 网络接口；模型请求仍使用原图 bytes，仅 UI 和本地持久化使用缩略图/文件引用。

## Test Plan

- 单元测试：v1/v2 会话迁移、图片引用序列化、保存 debounce、Markdown/LaTeX 缓存命中、错题筛选结果一致性。
- 仪器测试：聊天滚动、错题本筛选、图片预览、公式消息展示、存储迁移后重启恢复。
- Macrobenchmark：冷启动、聊天 100 条滚动、错题本 200 条滚动/搜索、发送含 3 张图片问题、流式回答 2 分钟。
- 验收指标：主线程长任务明显减少；关键滚动场景 jank 率低于 5%；含图片会话恢复时间下降至少 30%；长会话流式回答期间不出现连续明显卡顿。

## Execution Notes

- 已实现首批性能治理：会话图片文件引用与 v1 Base64 迁移、合并/防抖原子保存、异步图片预览解码、错题本 LazyColumn、聊天/教练/归档列表 contentType、Markdown/LaTeX 缓存、流式刷新节流、Release R8/资源压缩、JankStats 和 Macrobenchmark/Baseline Profile 场景。
- 复核后补齐执行缺口：导出/FlowStudy 上传改回便携 Base64 payload，本地保存继续使用 v2 图片文件引用；JankStats 改为 debug-only 依赖并通过反射启用，避免进入 release classpath；会话图片保存会跳过未变化文件，减少重复持久化 IO。
- 二次审计补齐图片引用元数据：`SessionImageStore` 保存图片引用时会从 PNG/JPEG/GIF/WebP 文件头读取 `width` / `height`，避免后续 UI/布局恢复需要重新完整解码。该项先新增 `SessionImageStoreTest.saveImagesRecordsKnownImageDimensions` 并确认失败，再补实现后通过。
- 已通过本地构建与核心测试：单元测试、Release 构建、benchmark APK 构建、AndroidTest 编译，以及聊天/错题本相关仪器测试。
- 最新本地验证：`.\gradlew.bat :app:testDebugUnitTest :app:assembleRelease :benchmark:assembleBenchmarkRelease :app:compileDebugAndroidTestKotlin --rerun-tasks` 通过，114 个任务全部执行完成。
- 标准 Gradle 真机仪器入口在当前 MIUI/HyperOS 设备上仍受系统限制：`:app:connectedDebugAndroidTest` 会在安装阶段失败，错误为 `INSTALL_FAILED_USER_RESTRICTED: Install canceled by user`，未进入测试执行。
- 真机仪器测试已通过 root 手动安装 APK 后执行：`StudyChatSessionStorageInstrumentedTest` 单独通过，覆盖 v1 Base64 图片迁移到 v2 文件引用。
- Compose/UI 真机仪器测试的挂起根因已确认：MIUI 私有后台启动限制拦截 AndroidX `ActivityScenario` 从 instrumentation 进程拉起 `TestComposeActivity`，logcat 出现 `MIUILOG- Permission Denied Activity`、`Abort background activity starts from 10297`，对应 app-op 为 `MIUIOP(10021)`。
- 在 MIX 2S Emerald Edition / Android 16 上，对 debug app 与 test app 临时执行 `adb shell cmd appops set com.studysuit.aiqa.debug 10021 allow` 和 `adb shell cmd appops set com.studysuit.aiqa.debug.test 10021 allow` 后，`StudyChatMessagesInstrumentedTest`、`MistakeBookWorkspaceInstrumentedTest`、`StudyChatHomeEntryInstrumentedTest` 手动 instrumentation 通过，10 个测试全部完成，用时 486.271s。
- MIUI 真机运行 Macrobenchmark 有设备前置条件：需要 root 安装 benchmark target/test APK，并预授权 benchmark 测试包的 `READ_EXTERNAL_STORAGE` / `WRITE_EXTERNAL_STORAGE`，否则 AndroidX Benchmark 内部 `GrantPermissionRule` 会在测试体执行前失败。
- 在当前 MIX 2S MIUI 环境中，root 预授权后 cold startup 不再立即因权限失败退出，但标准 AndroidX Macrobenchmark 仍会在应用场景启动前卡住。ANR 栈显示 `Instr: androidx.test.runner.AndroidJUnitRunner` 阻塞在 `androidx.benchmark.ShellKt.fullyReadInputStream` / `ShellImpl.<clinit>` / `MacrobenchmarkScope.cancelBackgroundDexopt`，同时设备上残留 `shellWrapper.sh` 和临时脚本进程，脚本内容为 `su root id`；直接 `adb shell su root id` 可快速返回，因此判断为 AndroidX Benchmark 通过 UiAutomation shell wrapper 探测 root 时被当前 MIUI/HyperOS 管道行为卡住。
- 已保留标准 Macrobenchmark/Baseline Profile 模块，同时新增本机 fallback：`scripts/run-miui-performance-smoke.ps1`。该脚本使用 `am start -W`、`dumpsys gfxinfo` 和 `framestats` 采集同一组内置 benchmark 场景；在 MIUI shell 无 `INJECT_EVENTS` 权限时，滑动交互改由 root `input swipe` 注入，普通设备仍可回退到 shell input。
- 最新 MIUI fallback 验证：`powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\run-miui-performance-smoke.ps1 -SkipBuild -SkipInstall -Iterations 1 -ScenarioWarmupSeconds 5` 通过，输出目录为 `build\performance\miui-smoke-20260620-052213`；`summary.csv` 覆盖 cold_start、chat_100_scroll、mistake_200_search、three_image_preview、formula_image_heavy、stream_2_minutes，未再出现 `SecurityException: Injecting input events requires ... INJECT_EVENTS permission`。

## Assumptions

- 按“全局治理”推进，不只修单个页面。
- 优先保证现有功能和数据兼容，不删除用户已有会话、归档、错题和图片。
- 性能指标以真实 Android 设备为准，模拟器结果只作辅助参考。
