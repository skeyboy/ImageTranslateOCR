# Smart Assist Core M0/M1 实施报告

状态：2026-07-27 已完成 Library First 的 M0/M1。本报告记录当时的独立 Library 阶段；后续确定性 App 接入见 [离线智能辅助 App 接入报告](smart-assist-app-integration-report.md)。

## 1. 目的

在不改变现有录屏、OCR、翻译和悬浮展示链路的前提下，先把智能辅助所需的协议、低频触发、上下文整理、保护规则、结果验证和失败回退做成独立 Library。后续模型能力只能作为可选 Provider 接入，用户未开启时不进入智能辅助路径。

本阶段优先保证：

- 核心逻辑可离线运行，不包含网络客户端、Android API、ML Kit 或模型 SDK。
- OCR 与翻译基线结果始终可用，智能结果只能形成受验证的建议。
- 新页面、关闭开关或失败回退能够使旧请求失效，避免过期结果回写。
- `:app` 暂不依赖 Library，模型选型和宿主集成可以分别决策。

## 2. 已实施范围

| 能力 | 实施结果 |
| --- | --- |
| 独立模块 | 新增纯 Kotlin/JVM `:smart-assist-core`，`:app` 未声明依赖 |
| 公共契约 | `SmartAssistEngine`、请求/结果、能力探测、Provider SPI、Library 自有几何与场景类型 |
| 低频触发 | 仅在用户强制请求、低置信度、上下文分组或展示优化需要时运行 |
| 确定性增强 | 场景分类、阅读顺序、文本分组、URL/代码保护、OCR 候选复核、长译文布局提示 |
| 安全验证 | 拒绝越界 Track、未知 Track、高置信度改写、跨脚本改写和未授权建议字段 |
| 并发与缓存 | latest-wins 过期丢弃、关闭/跳过请求同样使旧结果失效、SHA-256 键和 64 项 LRU 缓存 |
| 离线边界 | 生产源码禁止 Android、宿主、网络、ML Kit 导入；除 Kotlin 标准运行时外不显式声明生产依赖 |
| Fixture | 阅读、设置、混排、代码和越界异常共 5 组脱敏 JSON Fixture |

本阶段未实施 Gemini Nano、LiteRT、Gemini Computer Use Mobile、Mobile-Agent、Appium/AndroidWorld，也未新增无障碍权限。

## 3. 验证结果

离线命令：

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
  ./gradlew --offline :smart-assist-core:cleanTest :smart-assist-core:test
```

结果：14 个测试全部通过。

| 指标 | 当前结果 |
| --- | --- |
| 有效场景分类 | 4/4 Fixture 符合预期 |
| 分组数量契约 | 4/4 Fixture 落在预期范围 |
| URL/代码保护集合 | 15 个 Track 上与 Fixture 真值完全一致，其中 4 个应保护 |
| 低置信度候选修正 | 合法候选被接受 |
| 高置信度错误改写 | 被验证器拒绝 |
| 异常输入 | 越出 viewport 的 Track 在请求边界被拒绝 |
| Provider 不可用/异常 | 返回类型化跳过或失败，不抛向宿主 |
| 并发过期结果 | 新请求完成或被跳过后，旧结果均标记 `SUPERSEDED` |
| 离线依赖检查 | 通过 |

当前真值集规模只适合验证协议和回归逻辑，不代表真实业务准确率。进入 M2/M3 前仍需扩充真实脱敏样本并计算 CER/WER、保护规则精确率和展示越界率。

## 4. 运行时烟雾基线

在当前桌面 JBR 中预热 100 次后，两次分别对 1000 个不同 viewport signature 执行无缓存确定性分析：

| 指标 | 结果 |
| --- | --- |
| P50 | 72-75 微秒 |
| P90 | 139-221 微秒 |
| P99 | 356-523 微秒 |
| GC 后保留堆增量 | 0-12,328 字节（观测区间） |
| 结果缓存上限 | 64 项 |

原始报告生成在 `smart-assist-core/build/reports/smart-assist-core/runtime-smoke.txt`。这是工程烟雾基线，不是 Android 设备性能承诺；实际端侧 SLA 必须在 M2/M3 的目标机型上重新测量。

## 5. 结论与下一决策点

M0/M1 已证明智能辅助可以先以独立、离线、无 Android/宿主依赖的 Library 形式交付，并且不需要修改当前实时翻译链路。确定性 Provider 已能提供可验证的上下文和展示建议，同时保留关闭、不可用、失败和过期回退。

后续是否进入 M2，仍应由产品/研发明确选择是否启动端侧模型实验：

- 不启动：继续使用 `DeterministicAssistProvider`，保持零模型体积和全离线。
- 启动：分别建立可选 Gemini Nano 与 LiteRT Provider 原型，只比较离线质量、设备覆盖、延迟、内存和功耗，不接入 `:app`。

当前已按用户决策先完成确定性 Provider 的宿主 Adapter 和默认关闭开关；生成模型仍必须等 M2 独立报告证明有净增益后再接入。自动操作仍留在 M4 外部实验环境，不扩大线上无障碍权限。
