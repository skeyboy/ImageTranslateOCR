# v3 服务端重组与端侧回贴联合验证结论

日期：2026-08-07  
验证对象：历史请求 `475561a3-ba08-4b2e-8e53-670dd4a567fa`、`059c58d6-a136-4326-8dec-9a4b5586c0e7` 及失败样本回放请求 `f121cc9a-57aa-4472-ae1c-377373913d65`

## 1. 原始数据结论

| 记录 | 客户端组 | 服务端结果 | 关键表现 |
| --- | ---: | --- | --- |
| `475561...` | 6 | FAILED | 页面末段被客户端切为 3 个连续 BODY 组；旧规划器一次只合并相邻 2 组，剩余碎片仍单独送入 Qwen，模型返回空译文后整批失败。 |
| `059c58...` | 5 | SUCCEEDED，5→4 | 客户端预先把末两行合成一个组，服务端再合并相邻 2 组，形成双槽 FLOW_SLOTS，方向正确。 |

结论：问题不在 OCR 文字丢失，也不在端侧是否能画多槽文字；主要断点是服务端重组只能执行一次二元合并，无法连续吸收 3 个及以上的同一正文流。

管理页还有一个观察盲点：“服务端语义计划”只画边界和标签，没有在槽内显示 `sourceText`，因此无法直观看出服务端具体合并了哪些文字。

端侧存在三个绘制风险：

1. 服务端 `renderSlots` 被写入 OCR 的 `componentBounds`，原始 OCR 行框随之丢失；
2. 本地 Smart Assist 可能覆盖 v3 返回的布局提示，甚至保护性移除已由服务端成功翻译的组；
3. `sourceLineCount` 未进入实时绘制器，单个多行槽可能被误认为一行，导致字号和行高估算偏大。

## 2. 本次改造

### 2.1 服务端

- 语义计划升级为 `server-semantic-plan-v2`；
- 规划器以已合并组为当前正文流，继续评估下一个相邻组，直到几何、角色、标点续接或置信度条件不再成立；
- 合并后完整保留 `sourceGroupIds`、`memberRegionIds`、`sourceLineCount` 与所有 `renderSlots`；
- 管理页按槽高比例把 `sourceText` 分配到每个服务端槽，并保留组 ID、合并数量和置信度提示。

### 2.2 Android 端

- `componentBounds` 继续保存原始 OCR 行框，只用于字体和原文行高估算；
- 服务端 `renderSlots` 作为独立绘制几何保存，只用于擦除、回流、裁切和回贴；
- 实时绘制器优先消费服务端槽位和 `sourceLineCount`；
- 已存在服务端布局提示时，本地 Smart Assist 不再覆盖提示或移除该译文组；
- 最小字号比例按形状感知排版器的 `0.62` 下限执行，不再在数据映射阶段强制抬高到 `0.68`；
- 绘制缓存键加入相对槽位签名，同一文字但槽形改变时强制重新绘制。

## 3. 失败样本 v3 回放

管理页：<http://192.168.0.63:8090/admin/requests/f121cc9a-57aa-4472-ae1c-377373913d65>

请求摘要：

```json
{
  "schemaVersion": 3,
  "scene": "LIVE_SCREEN",
  "viewport": { "width": 1440, "height": 3200 },
  "clientGroupCount": 6,
  "regionCount": 22,
  "inputChars": 805
}
```

响应摘要：

```json
{
  "provider": "self-hosted-qwen-layout-plan-v3",
  "planVersion": "server-semantic-plan-v2",
  "mode": "AUTHORITATIVE",
  "metrics": {
    "clientGroupCount": 6,
    "plannedGroupCount": 4,
    "mergedGroupCount": 1,
    "authoritativeEligibleCount": 4
  }
}
```

末段合并结果：

```json
{
  "groupId": "server-semantic-b428754a--semantic-1aba83a8--semantic-ef36843e",
  "sourceGroupIds": [
    "semantic-b428754a",
    "semantic-1aba83a8",
    "semantic-ef36843e"
  ],
  "sourceLineCount": 4,
  "layoutShape": "FLOW_SLOTS",
  "sourceText": "Meanwhile,the Department of\nMeteorology and Hydrology under the Lao\nMinistry of Agriculture and Environmer\nissued warning on Wednesday that",
  "translatedText": "与此同时，老挝农业与环境部下属的水文气象部门于周三发出警告称",
  "renderSlots": [
    { "left": 33, "top": 2549, "right": 1010, "bottom": 2611 },
    { "left": 34, "top": 2662, "right": 1386, "bottom": 2841 },
    { "left": 33, "top": 2886, "right": 1208, "bottom": 2953 }
  ]
}
```

网页布局计算结果为 `FULL 3 + COMPACT 1`。末段译文在三个槽中完成回流，使用有限压缩，没有退回原文，也没有进入“更多”兜底。管理页的三个服务端槽分别显示第一行、中间两行和最后一行原文，人工可以直接确认合并边界。

## 4. 自动化验证

- Rust 服务端：13 个单元测试、7 个 API 集成测试全部通过；
- 新增链式合并测试：3 个连续正文组合并为 1 个 FLOW_SLOTS 组；
- Android JVM 定向测试：缓存槽位签名和 v3 布局合约共 5 项通过；
- APK：`:app:assembleDebug` 通过；
- 真机 `23113RKC6C`：ShapeAwareTextLayout 3 项定向测试通过，覆盖三槽绕排、17 行长正文完整回填和“更多”兜底边界。

## 5. 最终结论

本次事实验证支持“服务端负责高置信语义重组和声明式布局，端侧负责按槽安全绘制”的职责划分。对当前样本，服务端链式重组已消除导致 Qwen 空结果的末段碎片，端侧也能同时保留 OCR 行几何与服务端回贴几何。

仍需保留的边界：服务端只在角色相同、占用区域连续、水平关系一致、文本存在续接信号且置信度不低于 0.90 时链式合并；任一条件失败即停止吸收，避免跨卡片、跨气泡或跨栏合并。本次回放没有重新上传原始截图，因此完成的是同一 OCR/几何数据的精确回放与真机绘制算法验证，下一轮人工全屏采集可直接在上述管理页检查截图、计划和最终回贴三者的一致性。
