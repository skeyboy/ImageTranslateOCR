# v3 绕图正文错组与 Qwen 空译文修复验证

验证日期：2026-08-08

## 1. 样本与结论

- 原失败审计：`1f80a2e8-fb71-4f6c-8f2b-d71537151215`
- 原请求 ID：`59014756-b418-48e5-982f-7e514f6fcae5`
- 原页面：`http://192.168.1.4:8090/admin/requests/1f80a2e8-fb71-4f6c-8f2b-d71537151215`
- 最终重放审计：`b964820a-313f-4235-af08-faada01c1a50`
- 最终页面：`http://192.168.1.4:8090/admin/requests/b964820a-313f-4235-af08-faada01c1a50`

原记录不是 Android DSL 排版失败。服务端在生成响应和布局提示前已经因 `Qwen returned an empty translation` 返回 502；Android 随后只对短组执行本地降级，长正文按安全策略保留原文。因此失败现场中出现“标题局部中文、正文大面积英文”，但不存在一份成功的服务端 DSL 可供端侧完整绘制。

修复后同一份 26 个 OCR region、12 个端侧组请求连续两次重放成功。最终结果为服务端 12→8 组、5 组翻译、3 组结构性保留、0 组失败；原 4 个错位正文组合并为一个 10 行 `BODY/FLOW_SLOTS` 权威组，Qwen 返回完整非空译文，Android 契约允许消费该组的 4 个 renderSlots 和 10 个 sourceCoverSlots。

## 2. 原始失败链路

原请求事实：

| 项目 | 值 |
| --- | --- |
| viewport | 1440 x 3200 |
| OCR regions | 26 |
| 端侧 semantic groups | 12 |
| 输入字符 | 899 |
| 模型 | `qwen3.5-translation:9b` |
| 耗时 | 60626 ms |
| 结果 | `FAILED` |
| 错误 | `Qwen returned an empty translation` |

服务端原先只合并了页面标题 `AFRICA-CANADA.../Einstein centres`，图片右侧和图片下方的同一正文段仍被拆成四组：

1. `The Canadian government ... over the`
2. `next four years ... Mathematical`
3. `Sciences. The centres are spread`
4. `across the continent ... sciences.`

原模型请求使用数组结果。JSON Schema 只约束数组长度和 groupId 枚举，不能约束 groupId 唯一，也没有要求 `translatedText` 非空。重放得到的实际 Qwen 输出出现连续错位：

| 输出 groupId | 实际内容问题 |
| --- | --- |
| 第 1 个正文组 | 译文借用了后续“未来四年、五个中心”等内容 |
| 第 2 个正文组 | 继续借用下一组和段落后文 |
| 第 3 个正文组 | 实际翻译了第 4 组和后续段落 |
| 第 4 个正文组 | 实际翻译了下一自然段 |
| 最后一组 | `translatedText=""` |

模型是在按自然段理解文本，而服务端要求它按错误的半句边界逐组输出。末组为空只是错位链路的最终表现。

## 3. 服务端语义计划修复

`server-semantic-plan-v3` 增加三类可审计连续证据：

- `OCR_BLOCK_CONTINUATION`：同 ML Kit block 的连续行。
- `VISUAL_LINE_CONTINUATION`：垂直间距、水平重叠、左右边界和行高共同满足连续条件。
- `WRAPPED_MEDIA_FLOW`：正文在图片右侧逐行向左扩展，随后回到图片下方全宽区域。

行高优先使用 OCR region 高度；缺少原子行时使用 `groupHeight/sourceLineCount`，不再把整个多行组高度误当成一行高度。仅 `BODY/LIST_ITEM/TITLE` 能参与权威合并；跨 `TITLE/BODY` 时必须有同块或严格视觉连续证据，并在计划中记录 `ROLE_DRIFT_NORMALIZED`。长合并组规范为 `BODY`，从而获得长正文布局兜底。

最终主正文计划：

```json
{
  "role": "BODY",
  "sourceGroupIds": [
    "semantic-2a74e451",
    "semantic-5149c5ba",
    "semantic-2a1ba29f",
    "semantic-2db528cc"
  ],
  "sourceLineCount": 10,
  "layoutShape": "FLOW_SLOTS",
  "renderSlots": [
    {"left": 661, "top": 854, "right": 1323, "bottom": 1041},
    {"left": 484, "top": 1064, "right": 1387, "bottom": 1176},
    {"left": 487, "top": 1200, "right": 1291, "bottom": 1254},
    {"left": 29, "top": 1274, "right": 1334, "bottom": 1532}
  ]
}
```

## 4. Qwen 提示词与输出契约

只增强提示词不能解决本例。验证中先把输出改成 groupId 键控但保留错误分组，模型不再返回空值，却仍把后续内容借入前一键，并加入解释性注释。正确顺序必须是：先修复语义边界，再强化输出绑定。

提示词升级为 `semantic-translation-qwen-v6-keyed-group-binding`：

- 每个 groupId 是隔离的翻译单元。
- 文档上下文和邻组只能消歧，不能为当前键补写内容。
- 每个键只翻译同键 `sourceText`，不得预翻译下一键。
- 禁止空译文、摘要、解释、OCR 注释和跨组复制。

模型输出从数组改为键控对象：

```json
{
  "translations": {
    "<groupId>": {
      "translatedText": "...",
      "detectedSourceLanguage": "en",
      "targetLanguage": "zh"
    }
  }
}
```

Schema 为每个实际 groupId 生成独立属性，所有属性都在 `required` 中，`additionalProperties=false`，三个字符串字段均为必填，`translatedText.minLength=1`。服务端仍兼容解析旧数组结果，但新请求只声明键控 Schema。

## 5. 最终 Qwen 与 API 输出

最终主正文译文：

> 加拿大政府已拨款2000万加元（合1940万美元），用于在未来四年内资助非洲数学科学研究所（African Institute for Mathematical Sciences）的五个中心。这些中心遍布整个大陆，并通过下一代爱因斯坦中心（AIMS-Next Einstein Initiative）进行管理。它们将培训有才华的年轻非洲研究生研究人员从事数学科学研究工作。

关键布局提示：

```json
{
  "preferredMaxLines": 10,
  "minimumTextScale": 0.86,
  "lineSpacingMultiplier": 0.92,
  "overflowStrategy": "REFLOW_THEN_SCALE_THEN_MORE",
  "allowMore": true,
  "sourceLineCount": 10,
  "layoutShape": "FLOW_SLOTS"
}
```

金额 `C$20 million/US$19.4 million`、AIMS、AIMS-Next 和五个中心均保留。未再出现组间前移或末组空译文。

## 6. Android DSL 消费规则

Android 不重新推断服务端几何，继续严格校验：

- `sourceGroupIds` 必须存在、连续且不能被多个结果重复占用。
- `memberRegionIds`、`anchorBounds`、`renderSlots` 和 `sourceCoverSlots` 必须与原请求精确对应。
- `CONTROL/METADATA/TIMESTAMP` 等保护角色不能参与权威跨组。
- `TITLE/BODY` 跨角色仅在同 OCR block，或满足严格视觉连续边界时接受。

验收后端侧使用 `sourceCoverSlots` 清除全部参与翻译的原文，用 `renderSlots` 进行形状回流，再按实际排版生成 `usedRenderSlots`。本例长正文允许有限缩放和“更多”兜底，不会因为服务端误标为 TITLE 而禁用兜底。

## 7. 验证结果与边界

- Rust：30 个单元测试、8 个 API 测试通过。
- Android：完整 Debug JVM 测试通过；新增跨角色视觉连续契约覆盖通过。
- APK：`app/build/outputs/apk/debug/app-debug.apk` 构建成功。
- APK SHA-256：`252270d3bd56bd57d536d39abdb6e4c9b223e3ae67dbd3d42c6a62f16fe39697`。
- Web：最终审计显示 `AUTHORITATIVE 12→8组`，服务端语义计划和译文 renderSlots 均按四槽绕图结构还原。
- 服务：`0.0.0.0:8090` 正在运行，模型健康检查为 local/reachable/available。

本轮完成服务端真实 Qwen 重放、Web 还原和 Android 契约/APK 验证；没有把历史截图重新注入真机执行 OCR，因此“最终真机像素级回贴”仍需下一次设备实测截图确认。

**结论：本例的主因不是 Android 绘制算法，而是服务端把绕图自然段切成半句后，Qwen 按语义连续性跨组输出。服务端先恢复正确正文流、模型再按键控 Schema 翻译、Android 最后按权威 DSL 绘制，才能同时保证翻译完整性和版面一致性。**
