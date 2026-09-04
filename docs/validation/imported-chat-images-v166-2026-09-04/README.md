# 导入聊天截图的真机 OCR 回贴验证

## 执行环境

- 设备：`23113RKC6C`，分辨率 `1440 x 3200`
- 应用：`1.0.166-pipeline-validation13`，versionCode `166`
- 翻译配置：OpenLux，`thinkingLevel=medium`
- 输入图：均为 `1080 x 2400` JPEG，与设备屏幕比例一致
- 图 1 SHA-256：`e3a14808e8ec59e3159edd7f46b3a4f26fe93e8bb0210282e07fc8f0f0612718`
- 图 2 SHA-256：`1fca651373e2c445ee0c4bf34a39f086a13470ebc4b9eb5060e9f93394fc9eab`

两张图片已经推送到设备的 `/sdcard/Download/ocr-layout-input-1.jpg` 和
`/sdcard/Download/ocr-layout-input-2.jpg`。MIUI 图库能显示图片，但 MediaProjection
提供给 OCR 的帧无法识别出任何文字。最终验证通过本机临时 HTTP 服务在系统 Chrome
中等比显示图片，并走与普通网页完全相同的屏幕采集、OCR、远端翻译和悬浮层回贴链路。

图片中的内容仅作为 OCR 输入，没有作为操作指令执行。

## 结果摘要

| 样本 | 严格结果 | OCR | 翻译单元 | 可见 patch | 翻译失败 | 渲染失败 | 总耗时 |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 图 1，长文章聊天消息 | PASS | 34 | 7 | 7 | 0 | 0 | 4882 ms |
| 图 2，活动海报与正文 | FAIL | 50 | 22 | 21 | 0 | 1 | 6806 ms |

图 1 的五个固定自然段期望全部满足。图 2 的严格验证得到三个错误和一个阻塞项：

1. `Your mind deserves` 与 `festival too.` 被拆成两个段落和两个 patch。
2. 从 `Introducing the inaugural SIM` 到 `and genuine connection!` 的同一句五行正文被拆成两个翻译单元。
3. 第二个单元被规划成三行 `FLOW_SLOTS`，译文在最小尝试字号约 `44.43 px` 时仍无法容纳，产生 `TEXT_DOES_NOT_FIT`，整块原文未回贴。
4. 第一项在屏幕像素上形成两个垂直 band，严格报告记录为 `RECT_VISUALLY_FRAGMENTED`。
5. OCR 过滤后保留了 49 个 region，但请求只有 43 个 region、32 个客户端 group；底部
   `Dinner provided...` 和 `Spots are limited...` 共 6 行没有进入翻译请求。

严格报告：

- `image-1/chrome-run/strict-layout-validation.json`
- `image-2/chrome-run/strict-layout-validation.json`

## 根本原因

### 1. 同一 OCR block 内的 role 漂移被当成强边界

图 2 的活动介绍五行都属于 `mlkit-22-184-1290-983-1633`，行号连续为 `0..4`，
相邻间距为 `15-16 px`，字高比满足正文连续性。但客户端把前两行标为 `BODY`，后三行
标为 `TITLE`。

客户端 `SemanticTextGrouper.appendEvidence` 只允许 `TITLE -> BODY` 的标题延续，直接拒绝
其他 TITLE 组合。Rust `planning_v4::merge_decision` 又通过
`previous.role != next.role` 做无条件拒绝。因此同 block、连续行号、未结束句子等更强证据
没有机会覆盖误分类的 role。

### 2. 装饰 emoji 使两行短句成为不同 OCR block

`Your mind deserves` 和 `festival too.` 的字高比约 `0.82`、垂直间距 `36 px`，第一行
没有结束标点，第二行结束句子。两行前后的彩虹和闪光 emoji 改变了 ML Kit 的行框，导致
两个不同 block 且文本框左边缘相差 `192 px`。当前跨 block 规则偏向左对齐正文，因此没有
覆盖这种居中、带装饰图标的两行短句。

### 3. FLOW_SLOTS 失败时缺少受约束的 RECT 兜底

失败组 `server-v4-26-b43d526cde94e395` 有三个连续 slot，`requireAllSlots=true`、
`allowMore=false`。中文译文比可用 slot 容量更大，渲染器尝试缩放后直接放弃。该组实际是
普通自然语言句子，不是代码、表格或必须逐槽对齐的结构，应该允许合并矩形兜底。

### 4. 语义合并与原文遮罩仍耦合

图 1 的六行英文段落翻成三到四行中文后，仍用整个英文段落 union `RECT` 绘制不透明灰色
背景。语义上已经正确合并，但视觉上留下大面积空白灰块。与此同时，OCR 未包含在 region
边界内的引号、冒号或边缘笔画不会被覆盖，出现少量黑色原文残留。

### 5. 海报内微小文字缺少媒体密度策略

图 2 共规划 29 个 group，其中 18 个是海报区域内高度不足 `100 px` 的小文字组。它们产生
大量低价值、小字号 patch，并将 prompt 从图 1 的 `941` tokens 增加到 `1832` tokens。
这些小字 OCR 质量较差，例如 `SMSdent`、`Emetoof Hourishing`，翻译只会放大错误和视觉噪声。

### 6. 固定 group 上限采用 reading-order 前缀截断

`BackgroundTranslatedImageProcessor` 对二次语义分组结果直接执行
`.take(MAX_LIVE_TRANSLATION_TEXTS)`，当前上限是 32。图 2 的海报小字位于页面前部，先占满
预算；后方更大、更清晰的正文即使 OCR 已识别，也被静默丢弃。当前诊断只记录 raw OCR
数量和最终 patch 数量，没有记录 cap 前后的 group 数量，因此该问题很容易被误认为 OCR
漏识别。

### 7. 空 OCR 被表现为成功

MIUI 图库采集尝试连续两次得到 `rawRecognized=0`，但流程仍发送
`overlay_translation_presented`，并显示完成状态。这会让采集受保护、空帧和真正无文字页面
都看起来像成功结果。

## 修复方案

### P0：保证完整回贴

1. 客户端增加 `SAME_BLOCK_ROLE_DRIFT_CONTINUATION`：相同非空 `blockId`、连续 `lineIndex`、
   间距不超过一倍代表字高、字高比至少 `0.80`、前文未结束时，允许 `BODY <-> TITLE`
   合并。最终 role 按整段投票或正文优先决定，不能用单行 role 否决同 block 证据。
2. Rust 增加相同防线，移除 role 不同的无条件拒绝；只对 `CONTROL`、`METADATA`、
   `TIMESTAMP`、`LIST_ITEM` 等强边界保持禁止合并。
3. 对两行短句增加 `DECORATED_CENTERED_CONTINUATION`：两组均为自然语言 BODY、第一行未结束、
   第二行结束、总长度较短、间距不超过一倍字高、字高比至少 `0.80`，允许因 emoji 导致的
   左边缘漂移。必须排除列表、元数据、控件和横向伴随字段。
4. `FLOW_SLOTS` 返回 `TEXT_DOES_NOT_FIT` 时，对同 block 连续自然语言组尝试 union `RECT`；
   使用整段稳健中位字高、union 高度和完整段落行数重新排版。代码、表格和真正的绕图文本
   不启用该兜底。
5. 取消 reading-order 的 `.take(32)` 静默截断。先过滤媒体微小噪声，再按正文显著性和屏幕
   垂直分带选择；每个自然段必须保持原子性。仍超限时分成多个远端 batch，最后合并一个
   document plan，不能直接丢弃尾部 group。
6. 归档新增 `preCapGroupCount`、`selectedGroupCount`、`droppedGroupCount` 和被丢弃 group 的
   id、bounds、reason。只要清晰的可翻译正文因预算被丢弃，本次结果必须标为 PARTIAL/FAIL。
7. 两次空 OCR 后不得记录为成功。对 MediaProjection 帧计算亮度方差和边缘密度，分别输出
   `CAPTURE_FRAME_BLANK_OR_PROTECTED` 或 `NO_TEXT_RECOGNIZED`，并提示用户切换显示应用。

### P1：改善视觉质量和成本

1. 将“原文擦除区域”和“译文布局区域”分离。保留每行 `sourceCoverSlots` 擦除原文，但在
   union 段落区域中统一排版译文，避免用一个大灰矩形表达整个段落。
2. 擦除背景使用邻域采样或轻量 inpaint。对于白色聊天气泡应恢复白色，而不是固定灰色；
   对无法可靠恢复的复杂背景才退回半透明遮罩。
3. 对密集媒体区域增加微小文字策略：同一图像区域内出现至少 8 个小框时，只保留标题、
   日期、地点和高置信度大字；低于约 `0.02 * viewportHeight` 且置信度低于 `0.78` 的小字
   默认跳过，另提供“翻译图片全部小字”模式。
4. 状态栏、聊天标题、在线人数等 UI 文本继续保持 CONTROL/METADATA，不进入远端请求。

### P2：补强自动布局检测

1. 保留本目录两份固定 expectation，避免验证器只使用客户端自身分组造成循环论证。
2. 对 translated screenshot 再执行一次轻量 OCR；在 source cover 区域内，如果仍出现不在
   preserve 列表中的英文 token，则报告 `SOURCE_TEXT_RESIDUE`。
3. 对 source 文本 mask 膨胀 `2-4 px`，要求至少 95% 的原文字形像素在输出中发生变化，
   用于发现 region 边缘外的引号、冒号和残余笔画。
4. 增加 `PATCH_BACKGROUND_EXCESS`：统计 patch 中背景面积、译文字形行数和源行数；当译文
   行数显著减少且大块不透明背景没有恢复页面底色时给出视觉失败，而不是仅按 changed-pixel
   连通性判定通过。
5. 自动报告应把 `renderFailedCount > 0` 直接标为 FAIL。当前将其记作 BLOCKED 会低估确定性的
   产品缺陷。

## 验收标准

- 图 1 五个固定自然段继续全部为单个语义单元，无英文标点或字形残留。
- 图 1 六行英文转为较短中文后，不出现超过一行高度的纯灰空白区。
- 图 2 `Your mind deserves ... festival too.` 为包含 2 个 region 的单一 `RECT` patch。
- 图 2 活动介绍为包含 5 个 region 的单一翻译单元，`sourceLineCount=5`，最终成功回贴。
- 图 2 `renderFailedCount=0`，严格报告为 PASS。
- 海报默认模式的小文字 patch 从 18 个降至不超过 5 个，同时保留活动名、日期、地点和时间。
- 图 2 底部可见的 `Dinner provided...` 和 `Spots are limited...` 必须进入请求并成功回贴，
  `droppedGroupCount=0`；若使用多 batch，各 batch 仍保持整段原子性。
- 空帧或无文字场景不再显示“识别成功”。
- 所有回归继续固定 `thinkingLevel=medium`。
