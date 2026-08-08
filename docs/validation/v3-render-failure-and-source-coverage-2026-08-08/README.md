# v3 回贴失败审计与原文覆盖几何验证（2026-08-08）

## 1. 验证目标

本轮只处理两类可由真实数据证明的问题：

1. Debug 模式下，服务端翻译成功但端侧布局或绘制失败时，也要上传失败现场，不能只记录成功回贴截图。
2. 端侧遮罩必须覆盖所有参与组级翻译的 OCR 成员文字，同时不能把组外图片、控件和相邻文本一并遮住。

最终职责拆为两个独立几何集合：

- `sourceCoverSlots`：必须清除的原文成员区域，由 OCR 原子 region 的 `componentBounds/bounds` 生成。
- `renderSlots`：允许排译文的区域，由端侧初步组和服务端权威组的版面计划生成。

绘制背景使用 `sourceCoverSlots ∪ usedRenderSlots`，文本排版只使用 `renderSlots`。其中 `usedRenderSlots` 是排版成功后实际承载译文的槽位，不能在排版前把所有允许槽都涂成背景。两套输入几何和一个运行期结果不能再共用同一个字段。

## 2. 最新真实记录对比

| Audit | OCR/端侧组 | 服务端结果组 | 版面特征 | 实际问题 |
| --- | ---: | ---: | --- | --- |
| `57f262ee-5708-4d7c-bd9a-855611b41112` | 8 region / 2 组 | 2 组 | 1 行标题 + 7 行正文，均为单矩形 slot | 回贴灰底仍保留英文纹理，译文与原文同时可见 |
| `5894972e-720b-48c1-8fee-1cac58a613ed` | 25 region / 7 组 | 6 组 | 服务端合并 1 次；长正文 13 行、2 个 `FLOW_SLOTS` | 译文提前在首 slot 放完后，第二 slot 被恢复成原图，两行英文漏出 |
| `e07485c9-4fcd-4832-bbbf-35d022fc8c43` | 26 region / 5 组 | 5 组 | 最长正文 11 行、单矩形 slot | 大段覆盖基本完整，但仍缺少可区分成功/局部失败的端侧诊断 |

这些记录证明服务端分组和 Qwen 完整译文本身不是主要根因。尤其 `589...` 的权威组包含完整 13 个 `memberRegionIds`，`preferredMaxLines=13`，两个 `renderSlots` 也都已返回；原文漏出发生在 Android 绘制阶段。

## 3. 根因

### 3.1 成功截图策略排除了失败结果

旧端侧上传策略要求 `patchCount > 0`。当译文已返回但没有任何区域能生成 patch 时，`failedCount > 0`、`patchCount = 0`，因此不会采集失败现场。服务端上传接口又只允许关联 `SUCCEEDED` 请求，无法保存远端失败后的原屏或局部回贴状态。

### 3.2 `renderSlots` 同时承担排版与擦除职责

旧绘制器只在 `renderSlots` 生成替换背景。服务端合并或图片绕排时，译文允许区域与原始成员行范围并不总是完全相同，单靠 `renderSlots` 无法证明每个 OCR 成员已被覆盖。

### 3.3 未使用 slot 恢复原图

旧逻辑在译文提前排完时，把未使用的后续 slot 从源图复制回来。这能消除空灰条，却会把已经参加整组翻译的原文重新画回屏幕。`589...` 的末两行泄漏正是该分支造成。

### 3.4 模糊底图保留了文字纹理

声明式 v3 组仍可能走 `BLUR_TINT/FEATHERED_BLUR_TINT` 背景。这类材料适合保留图片细节，不适合清除高对比文字；`57...` 中的英文透出属于背景保真策略与文字替换目标冲突。

## 4. 修复后的 v3 DSL

服务端每个 `layoutHint` 新增：

```json
{
  "renderSlots": [{"left": 71, "top": 1219, "right": 1363, "bottom": 2089}],
  "sourceCoverSlots": [
    {"left": 71, "top": 1219, "right": 1320, "bottom": 1278},
    {"left": 71, "top": 1301, "right": 1288, "bottom": 1360}
  ]
}
```

`sourceCoverSlots` 由请求中每个 `memberRegionId` 对应 region 的 `componentBounds` 生成；没有 component 时使用 region `bounds`。Android 会校验服务端返回值与本地无损 OCR 成员几何完全一致，防止服务端越界扩大遮罩。

失败或成功的回贴截图上传结构为：

```json
{
  "sessionId": "...",
  "generation": 26,
  "translationRevision": 2,
  "outcome": "RENDER_FAILED",
  "stage": "OVERLAY_PARTIAL_DRAW",
  "failureCode": "PARTIAL_RENDER",
  "failureMessage": "1 translated region(s) were not pasted back",
  "layoutDiagnostics": {
    "schemaVersion": 1,
    "recognizedCount": 8,
    "translatedRegionCount": 2,
    "renderedPatchCount": 1,
    "failedRegionCount": 1,
    "sourceRegionCount": 2,
    "sourceCoverageRatio": 0.12,
    "patchRegionCount": 1,
    "patchCoverageRatio": 0.08,
    "renderingMode": "PARALLEL",
    "backgroundMode": "ADAPTIVE"
  },
  "capture": {"mimeType": "image/jpeg", "pixelWidth": 486, "pixelHeight": 1080, "dataBase64": "..."}
}
```

旧客户端不发送新增字段时按 `outcome=PRESENTED` 兼容。故意滑动取消仍不作为回贴失败上传。

## 5. 端侧绘制规则

1. OCR 原子 region 和 `memberRegionIds` 保持无损，不能先合并成只有外接矩形的数据。
2. 服务端权威组只改变翻译单元、阅读顺序和 `renderSlots`，不能改写 `sourceCoverSlots`。
3. 先完成排版，再把替换背景限制为 `distinct(sourceCoverSlots + usedRenderSlots)`；未承载原文、也未承载译文的安全槽保持透明。
4. 译文只在 `renderSlots` 中通过形状感知排版器回流。
5. `FLOW_SLOTS` 长文按槽面积分配到全部可用槽，避免图片下方形成空槽；极短文本不强制全槽分配。后续 slot 如果属于 `sourceCoverSlots`，即使没有译文也继续保持遮罩，禁止恢复原文。
6. v3 声明式组不再用保留文字纹理的模糊背景；patch 在覆盖形状外保持透明，避免遮挡相邻图片或控件。
7. 全部排版失败时恢复原图并上报 `RENDER_FAILED/OVERLAY_LAYOUT`；局部 patch 失败时保留成功 patch 并上报 `RENDER_FAILED/OVERLAY_PARTIAL_DRAW`。

该规则修订了 2026-08-07 “未使用 slot 一律恢复原图”的结论。现在只有不属于 `sourceCoverSlots` 的未使用安全区域可以透明；参与翻译的原文字形范围必须始终被遮住。

## 6. Web 人工核验

Admin 右侧“译文与 renderSlots”新增棕色虚线 `sourceCoverSlots` 层，蓝色仍表示译文允许区域。这样可直接检查：

- 每个 OCR 成员行是否都有棕色覆盖形状；
- 蓝色译文槽是否越过权威组边界；
- 图片绕排产生的多槽是否只占允许区域。

回贴截图仍使用原图旁的单一复选框切换。失败记录显示“显示端侧失败现场”，元数据包含失败阶段和原因；详细覆盖诊断默认折叠，不扩大页面主版面。

## 7. 已执行验证

- 同一 `57...` v3 请求重放成功，新记录 `8e58ccb3-8529-43b6-96f8-ce4ef1166ba7` 返回 2 个结果、2 个 `renderSlots`、8 个 `sourceCoverSlots`；Qwen 两组均完整返回。
- Web 新记录还原得到 8 个原文覆盖元素和 2 个译文 slot，控制台无错误。
- Rust：17 个单元测试、7 个 API 测试通过；API 测试覆盖 `sourceCoverSlots`、失败截图 DSL、数据库落库和 Admin 失败现场显示。
- Android：185 个 Debug JVM 测试通过；主 APK、AndroidTest APK 构建成功。
- 主 APK 已安装到真机 `23113RKC6C`，自建 v3 地址调整为 `http://192.168.1.4:8090`，OCR 原图与回贴后截图上传开关均开启。
- 新增设备级几何用例：译文只使用第一行 slot 时，第二个 OCR 成员行仍必须被覆盖。测试 APK 安装被手机安全策略 `INSTALL_FAILED_USER_RESTRICTED` 拦截，随后无线 ADB 端口离线，因此本轮暂未把该断言标为真机执行通过。

## 8. 当前结论

最新数据支持本轮改造：服务端负责返回可验证的原文覆盖几何与译文布局计划，Android 分开执行擦除和排版；失败现场也进入同一个请求审计链路。该方案比继续扩大单个外接矩形更可靠，因为它同时约束“不能漏原文”和“不能遮组外内容”。

当前自动验证已覆盖协议、数据库、Web 和 Android 编译/JVM 逻辑。真机主 APK 已更新，但设备级几何测试仍受手机安装策略和无线 ADB 状态影响；恢复连接后应优先执行 `ScreenshotOverlayLayoutTest#sourceCoverSlotsEraseMemberTextOutsideTheUsedTranslationSlot`，再用同一 Rust 页面做一次完整录屏回贴。

## 9. 第三步绘制优化（2026-08-08）

按当前验证顺序，本节暂不继续追踪失败截图案例，只处理成功结果的几何回贴。

### 9.1 真实记录提供的输入事实

真机标准模式记录 `72e769cb-c999-4b0d-ac9a-c818914fe929` 对应请求 `f2825c32-b87b-4a14-9541-0a49256117e1`：端侧 17 组经服务端整理为 15 组，服务端合并 1 次；12 组翻译、3 组保护、0 组失败，总耗时 81999ms。Android 生成 12 个 patch，回贴失败数为 0；114 个参与翻译的拉丁原文 token 中残留 0 个，说明 `sourceCoverSlots` 已解决原文漏出。

图片绕排正文组 `server-semantic-95f58cb6--semantic-15596eee--semantic-eb4beee` 由 3 个源组组成，服务端返回 3 个 `renderSlots` 和 7 个 `sourceCoverSlots`。真实回贴没有覆盖相邻图片，但旧排版器在前两个槽已经用完译文，仍把第三个允许槽绘制成背景，形成图片下方的空灰条。这不是 OCR、翻译或分组失败，而是“允许绘制范围”被误当作“实际绘制范围”。

### 9.2 新绘制顺序

1. Android 先将服务端 `renderSlots` 裁剪到当前 bitmap，并完成形状感知排版。
2. `FLOW_SLOTS` 且文本量足够时，按各槽面积占比分配译文字符，要求所有槽均得到文本段；正文由图片右侧自然流向图片下方。
3. 少于“每槽 2 个非空白字符”的短文本不强制使用全部槽，避免短标签因槽数过多而失败。
4. 排版成功后取得 `usedRenderSlots`，背景只覆盖裁剪后的 `sourceCoverSlots + usedRenderSlots`。
5. 未参与原文擦除、也没有承载译文的声明式安全区域不绘制背景，从机制上消除额外空块。
6. `sourceCoverSlots` 在裁剪后直接参与集合计算，不再通过未裁剪矩形相等反向筛选，修复屏幕边缘可能漏遮罩的问题。

### 9.3 验证边界

- 三槽图片绕排的定向 AndroidTest 已加入，断言完整中文会按顺序使用服务端声明的全部 3 个槽，且不触发“更多”。
- `testDebugUnitTest`、主 Debug APK 和 AndroidTest APK 均构建成功，共 137 个 Gradle task（9 个执行、128 个复用缓存）。
- 上述真机记录证明改造前的 `sourceCoverSlots` 已达到“无英文残留、无图片越界遮挡”；它同时暴露了第三槽空块，因此是本次算法修订的输入证据，不是修订后的最终视觉证明。
- 遵照当前指令，失败案例和本次新算法的真机重跑暂时跳过。新的全槽分配与按实际槽遮罩目前只标记为自动化测试编译通过，待后续统一执行视觉验收。

**本步结论：端侧绘制应采用“服务端声明允许槽、端侧计算实际槽”的两阶段模型。原文必须按 `sourceCoverSlots` 全量擦除；额外背景只能覆盖真正承载译文的 `usedRenderSlots`。**
