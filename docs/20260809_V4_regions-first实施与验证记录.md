# V4 regions-first 实施与验证记录

> 日期：2026-08-09
> 分支：`codex/v4-regions-first`
> 设计依据：`docs/20260809_v3_OCR翻译与DSL回贴全流程设计.md`

## 1. 本轮目标

V4 不替换现有 v3，而是新增一条可切换、可审计、可回滚的 regions-first 流程：

1. Android 继续上传无损原子 OCR regions 和客户端建议组。
2. 服务端以 regions 为权威输入，允许拆分客户端过度合并组，也允许合并欠分组行。
3. Qwen 只根据服务端固定组执行整组翻译，不生成坐标和最终换行。
4. 服务端返回确定性的 `memberRegionIds`、`sourceCoverSlots`、`renderSlots` 和 layoutHint。
5. Android 按原子成员执行响应校验，使用真实字体测量完成回贴。

## 2. 新增入口

| 模块 | V3 | V4 |
| --- | --- | --- |
| 翻译 API | `/api/v3/translate/layout-plan` | `/api/v4/translate/layout-plan` |
| 回贴截图上传 | `/api/v3/translate/requests/{requestId}/rendered-capture` | `/api/v4/translate/requests/{requestId}/rendered-capture` |
| schemaVersion | 3 | 4 |
| 服务端计划版本 | `server-semantic-plan-v3` | `server-regions-first-plan-v4` |
| Provider | `self-hosted-qwen-layout-plan-v3` | `self-hosted-qwen-regions-first-v4` |
| Android 入口 | 自建 v3 | 自建 v4 |

Android 的 v3/v4 共用服务基址和 Bearer Token；版本选择决定实际路径和响应校验规则。

## 3. 服务端实现

### 3.1 V4 协议

- V4 的 `regions` 必填且具有权威性。
- `groups` 允许为空；存在时仅作为 advisory 信息。
- `regions[].groupId` 在 V4 中允许为空；Android 当前仍提供该字段用于客户端血缘追踪。
- V2/V3 原有“每个 region 必须恰好属于一个客户端 group”校验保持不变。
- V4 接受服务端把同一客户端 group 拆成多个结果，结果通过 `memberRegionIds` 保持唯一归属。

### 3.2 regions-first 规划

新增 `demo-server/src/planning_v4.rs`：

```text
原子 regions
  -> readingOrder 排序
  -> 原子起组
  -> 时间戳/标识符/CODE 强边界
  -> 大间距、字号差、栏边界拆分
  -> OCR block、视觉连续、图片绕排合并
  -> 高置信 AUTHORITATIVE documentPlan
```

当前合并证据包括：

- `OCR_BLOCK_CONTINUATION`
- `VISUAL_LINE_CONTINUATION`
- `WRAPPED_MEDIA_FLOW`
- `CLIENT_GROUP_ADVISORY`

当前强边界包括：

- 独立时间戳；
- URL、邮箱等标识符；
- `GET/POST/PUT/PATCH/DELETE + API path`；
- 短大写章节标签；
- 大垂直间距；
- 明显行高差；
- 不兼容的语义角色。

### 3.3 Qwen 边界

V4 复用现有 keyed JSON 模型协议。Qwen 接收到服务端规范组、整页上下文、regionLines 和归一化几何；仅返回：

```json
{
  "translations": {
    "server-v4-group-id": "译文"
  }
}
```

成员 ID、坐标、遮罩槽位和绘制策略仍由确定性代码生成。

### 3.4 Admin

- 历史列表增加 v4 过滤项。
- 列表和详情页增加 v4 标记。
- V4 request/response、模型请求、documentPlan、译文和 renderSlots 沿用现有详情回显。
- Admin 中的服务端语义计划直接显示 regions-first 合并后的 `sourceText` 和成员。

## 4. Android 实现

### 4.1 后端入口

`TranslationBackend` 新增 `SELF_HOSTED_V4`，主程序高级设置和全屏悬浮窗 Provider 菜单均提供“自建 v4”。

选择 V4 后：

- 请求 schemaVersion 为 4；
- 翻译地址切换到 V4；
- debug OCR 截图和回贴截图继续支持；
- 取消仍复用 requestId 取消通道；
- translation trace 记录 schemaVersion，回贴截图上传到对应版本路径。

### 4.2 响应安全校验

V3 仍以客户端 group 为主要绑定单元。V4 改为以下不变量：

1. `documentPlan.mode == AUTHORITATIVE`。
2. `documentPlan.planVersion == server-regions-first-plan-v4`。
3. 每个 `memberRegionId` 必须来自当前请求。
4. 每个原子 region 在所有结果中只能出现一次。
5. 所有请求 region 都必须由 TRANSLATED、PRESERVED 或 FAILED 结果覆盖。
6. `sourceGroupIds` 必须能够从成员 region 的客户端血缘重新推导。
7. 多 region 合并结果必须达到权威置信阈值。
8. anchorBounds 必须等于成员外包围。
9. sourceCoverSlots 必须等于原子 componentBounds/bounds。
10. renderSlots 必须等于服务端从本组原子 region 形成的允许绘制槽位。

### 4.3 回贴映射

V4 不再通过 `sourceGroupIds` 获取整块端侧组进行绘制。Android 使用 `memberRegionIds`：

```text
V4 result.memberRegionIds
  -> 找到请求中的原子 OCR regions
  -> 按 readingOrder 组合 sourceText
  -> 计算本结果的原文 bounds/componentBounds
  -> 使用 DSL renderSlots 做 ShapeAwareTextLayout
  -> 使用 sourceCoverSlots 擦除原文字
  -> 绘制并原子提交
```

这样同一个客户端组被拆为两个服务端结果时，不会重复遮罩整个客户端组。

如果某个服务端结果未通过 Android 语言质量校验，该结果会生成明确失败执行：保留原文、计入 failedCount，并可触发开发模式失败回贴截图上传。

## 5. 场景验证

场景请求保存在：

- `demo-server/examples/v4-chat-request.json`
- `demo-server/examples/v4-article-request.json`
- `demo-server/examples/v4-technical-request.json`

### 5.1 聊天场景

输入特征：客户端把 3 行长聊天正文和 `22:43` 错误合成一个 group。

服务端 V4 结果：

| 规范组 | 成员 | 处理 |
| --- | --- | --- |
| BODY | `chat-1, chat-2, chat-3` | Qwen 整组翻译 |
| TIMESTAMP | `chat-time` | PRESERVED |

真实 Qwen 译文：

```text
Web3 行业尝试过，我们做得相当不错。
直到国家决定投资一些不符合社区利益的东西。
他们肯定会回来的。
```

结论：V4 能拆分一个过度合并的客户端组；两个结果可共享 `client-chat-overmerged`，但原子成员和覆盖区域互不重叠。

### 5.2 文章阅读场景

输入特征：标题独立，正文三行从图片右侧逐步回到全宽区域。

服务端 V4 结果：

| 规范组 | 成员 | 处理 |
| --- | --- | --- |
| TITLE | `article-title` | 标题翻译 |
| BODY/FLOW_SLOTS | `article-1, article-2, article-3` | 绕排正文整组翻译 |

真实 Qwen 译文：

```text
非洲 - 加拿大：下一代爱因斯坦中心获得提振
加拿大政府已在今后四年内向非洲数学科学研究所的五个中心拨款 2,000 万加元。
```

结论：标题没有被并入正文，`C$20 million` 的金额语义得到保留，正文 DSL 保持三个原始占用槽位。

### 5.3 技术文档场景

输入特征：大写章节标签、两行说明正文和 API 路径。

第一次运行发现 `POST /api/v4/translate/layout-plan` 被当作 BODY 送给模型。虽然模型原样返回，但协议依赖模型保持代码不可靠。

修正后：

| 规范组 | 处理 |
| --- | --- |
| `API REFERENCE` | 独立翻译 |
| 两行说明正文 | 整组翻译 |
| `POST /api/v4/translate/layout-plan` | CODE + PRESERVED，不发送给 Qwen |

结论：技术标识符保护改为确定性规则，不依赖模型碰巧原样输出。

## 6. 自动化与真机验证

已执行：

```bash
cd demo-server
cargo fmt
cargo test

JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
  ./gradlew :app:testDebugUnitTest

JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
  ./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class='com.example.imagetranslate.translate.SelfHostedSemanticTranslationProviderInstrumentedTest#configuredV4ServiceTranslatesAtomicRegionsOverRealNetwork' \
  -Pandroid.testInstrumentationRunnerArguments.selfHostedBaseUrl='http://192.168.1.9:8090'
```

验证环境：

- 服务：`0.0.0.0:8090`
- 模型：本地 `qwen3.5-translation:9b`
- 真机：`23113RKC6C`，ADB 地址 `192.168.1.12:41209`
- 真机可访问服务：`http://192.168.1.9:8090`
- V4 真实网络 instrumentation：1/1 通过
- Android JVM 单元测试与 `assembleDebug`：通过
- 服务端测试：38 个单元测试、9 个 API 测试，共 47 个通过

补充的“为已安装主程序写入 V4 人工验收配置”设备测试在测试 APK 安装阶段被设备系统以
`INSTALL_FAILED_USER_RESTRICTED` 拒绝，实际测试体未执行。这属于设备侧安装确认限制，不是
V4 协议或网络失败；本轮已有同一设备、同一服务地址的 V4 真实网络 instrumentation 通过作为
链路验证依据。

静态检查说明：Android `lintDebug` 在当前 Android Gradle Plugin / Lifecycle lint 组合中因
`KaCallableMemberCall` 类/接口不兼容而崩溃；服务端 `cargo clippy --all-targets -- -D warnings`
命中了既有模块中的风格警告。两项均未发现 V4 运行时失败，本轮以编译、单元/API 测试和真机
真实网络测试作为交付门槛，后续应单独升级 lint 工具链并清理历史 clippy 告警。

### 6.1 长页面 Qwen 超时修复

失败记录 `512c410b-2b16-490b-816c-64a486e13d3f` 在 90,014 ms 时返回
`Qwen request failed: error sending request`。记录中包含 27 个 OCR 原子区域和 13 个端侧参考组，
V4 重建后形成 22 个模型翻译单元，输入正文约 899 字。直接向正常在线且已加载的 Ollama 重放
相同模型请求，165.83 秒后获得 HTTP 200，确认根因是固定 90 秒超时，并非 Ollama 端口不可达。

修复内容：

1. 本地 Qwen 请求按翻译组数量使用 90 至 210 秒自适应预算，仍受
   `QWEN_TIMEOUT_SECONDS` 配置上限约束。
2. Android 自建服务 HTTP 读取时限调整为 220 秒，翻译流程总时限调整为 225 秒。
3. 服务端分别报告 Qwen 超时和连接失败，避免将两类故障统一显示成含糊的发送失败。

使用原始 V4 请求去除已脱敏的调试图片字段后完整重放，104.1 秒成功返回 23 个结果：22 个
`TRANSLATED`、1 个 `PRESERVED`、0 个失败。该结果已越过原 90 秒故障边界。

## 7. 当前限制与下一步

本轮目标是跑通 V4 主流程，尚未大规模调节分组阈值。下一轮应优先观察：

1. 无 advisory group 时，标题和控件角色的几何识别准确率。
2. 双栏页面中相邻行是否存在跨栏误合并。
3. 长聊天气泡拆分后 Android 真机 `MORE` 与最小字号策略。
4. 图片绕排场景的 renderSlots 是否需要合并相邻同形行槽以提高排版利用率。
5. Web 结构还原与 Android 字体实测结果之间的差异是否来自字体、行距还是槽位。
6. V4 高置信权威组接受率、Android 拒绝原因和失败截图闭环。

V3 路由、设置项和响应校验均被保留；发现 V4 场景回归时可直接切回“自建 v3”，不需要回滚服务端数据库或 Android 安装包。
