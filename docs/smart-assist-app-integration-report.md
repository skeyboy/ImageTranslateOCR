# 离线智能辅助 App 接入报告

状态：2026-07-27 已完成 M3 的确定性离线接入和悬浮窗可选开关；端侧生成模型尚未接入。

## 1. 目的

将 `:smart-assist-core` 作为可选 Library 接入实时屏幕翻译，同时保证用户拥有明确控制权：功能默认关闭，只有用户在悬浮窗中主动开启后才执行智能辅助；关闭、失败或无建议时继续使用原有 OCR、翻译和悬浮展示结果。

## 2. 用户入口

路径：识别悬浮窗 → 设置图标 → 识别策略设置 → `离线智能辅助`。

- 菜单项使用勾选状态表示开启/关闭。
- 初始值为关闭。
- 用户选择通过本地 SharedPreferences 持久化，服务重启后恢复。
- 识别会话运行中切换时，当前旧帧立即失效，并按新状态重新采集。

## 3. 当前行为

关闭时：

- 不创建智能辅助请求，不调用 `SmartAssistEngine`。
- 原有 OCR、翻译、差分刷新、贴片渲染和权限行为保持不变。

开启时：

- 快速 OCR 完成后，由 `LiveSmartAssistAdapter` 先识别受保护内容，并只把 2 至 4 个相邻正文区域合并为翻译上下文；标题、控件、URL、代码和品牌不跨区合并。
- 翻译完成后再次应用经过验证的保护和布局建议，再进入既有贴片渲染。
- 当前使用 `DeterministicAssistProvider`，完全本机运行，不包含网络调用和模型下载。
- URL、代码等受保护内容不会生成覆盖贴片，保留底层原始内容。
- 长译文布局建议仅在能够完整容纳文本时生效；无法满足时回退原排版算法，不截断文字。
- 场景、文本组、保护数量、布局建议数量和耗时只以计数形式写入现有日志，不记录屏幕原文。
- Provider 不可用、异常或无有效建议时直接保留基线结果。

当前 OCR 数据提供置信度但没有候选文本列表，因此 Library 的“候选纠错”能力尚未在宿主生效。后续只有在 OCR 层提供可信候选后才接入，不能让模型直接改写高置信度结果。

## 4. 代码边界

| 组件 | 职责 |
| --- | --- |
| `:smart-assist-core` | 协议、规则、验证、缓存和 Provider SPI |
| `LiveSmartAssistAdapter` | Android 宿主数据与 Library 数据映射、异常回退 |
| `LiveSmartAssistPreferences` | 默认关闭和本地持久化 |
| `ActiveScreenCaptureOverlayController` | 悬浮设置菜单和开关事件 |
| `BackgroundTranslatedImageProcessor` | 在渲染前应用保护与安全布局建议 |
| `OneShotScreenCaptureService` | 读取状态、使旧帧失效、重新采集和计数遥测 |

未新增 Manifest 权限、无障碍能力、网络客户端或云端 Agent 依赖。

## 5. 验证

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
  ./gradlew --offline :smart-assist-core:test :app:testDebugUnitTest
```

- `:smart-assist-core`：16/16 项测试通过。
- `:app`：117/117 项 JVM 测试通过，其中包含默认关闭、离线保护/布局映射、上下文分组、缓存和 Adapter 失败回退测试。
- Debug Kotlin 主代码和测试代码编译通过。
- Debug 与 AndroidTest APK 打包并通过 ADB 覆盖安装。
- 真实 ML Kit OCR + 英译中语义黄金测试 1/1 通过：5 条样本 CER/WER 均为 0，译文精确率 100%，OCR 框平均 IoU 89.12%。
- `git diff --check` 通过。

Android Lint 当前无法完成：Lifecycle 的 `NullSafeMutableLiveData` 检查器在分析既有 `App.kt` 时与 Kotlin Analysis API 发生 `IncompatibleClassChangeError`。这是 Lint 工具链崩溃，未通过全局禁用规则绕过；需要后续升级兼容的 AGP/AndroidX Lint 组合后复验。

## 6. 结论

离线智能辅助现在已经作为用户可选功能接入悬浮窗。默认关闭时保持原链路；开启后只运行确定性本机增强，并由现有验证器和宿主回退共同限制结果。Gemini Nano、LiteRT、Gemini Computer Use Mobile、Mobile-Agent 和自动操作仍未启用，也没有扩大线上无障碍权限。

当前结论是保留确定性离线辅助并继续默认关闭。4 轮整屏 A/B 未证明稳定性能或语义优势，因此不默认开启；`BALANCED` 差分虽然出现 3 次 Track 缓存命中，但面积召回仅 80.94%，已拒绝进入生产默认路径。下一步先扩充真实页面人工黄金集，再决定是否进入可选端侧模型 Provider 实验。
