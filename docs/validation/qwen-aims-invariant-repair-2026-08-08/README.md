# Qwen AIMS/AIMS-Next 标识符丢失修复验证

验证日期：2026-08-08

## 1. 样本与直接结论

- 原失败审计：`f9f9190d-566d-4be8-8093-b1cb9462d76e`
- 原页面：`http://192.168.1.4:8090/admin/requests/f9f9190d-566d-4be8-8093-b1cb9462d76e`
- 原请求 ID：`381c5701-ac21-4860-8fdc-3b879b24c0ca`
- 修复后审计：`80ca7b80-45e5-4c49-9d97-f3777db9a9b3`
- 修复后页面：`http://192.168.1.4:8090/admin/requests/80ca7b80-45e5-4c49-9d97-f3777db9a9b3`

该记录不是 OCR、服务端分组、JSON 解析、网络或 Android DSL 错误。Qwen 返回了完整、可解析且 groupId 齐全的 JSON，但把源文中的 `AIMS-Next` 意译成“下一代爱因斯坦中心计划”。服务端关键字段校验随后拒绝整批响应，因此请求显示失败，端侧没有可消费的 v3 成功响应。

## 2. 原始输入与模型输出

失败组是服务端把图片右侧和图片下方正文恢复为一个自然段后的权威组：

```text
groupId: server-semantic-2458fd0--semantic-e35a53dc--semantic-f9c1a25
The Canadian government
has awarded C$20 million
(US$19.4 million)over the
next four years to five centres of the
African Institute for Mathematical
Sciences.The centres are spread
across the continent and run through the AIMS-Next
Einstein Initiative.They will train talented young
African postgraduate researchers in mathematical
sciences.
```

将审计中保存的实际 Ollama 请求原样重放，Qwen 返回合法 JSON，关键译文为：

```text
加拿大政府已拨款 2,000万加元（合 1,940万美元），在接下来四年内资助五个非洲数学科学研究所。
这些中心遍布整个大陆，并通过下一代爱因斯坦中心计划进行管理。
它们将培训有才华的年轻非洲研究生研究人员从事数学科学研究。
```

同一批次的下一段也把 `AIMS-Next` 意译掉了。原校验使用 `source.contains("AIMS")`，因此虽然实际丢失的是 `AIMS-Next`，错误文本只报告“lost the AIMS identifier”。校验阻止了术语静默损失是正确的，但诊断粒度和失败恢复不足。

## 3. 修复方案

提示词版本升级为 `semantic-translation-qwen-v8-literal-role-preservation`，修复分为三层。

### 3.1 每组显式送模约束

模型输入的每个 `translateGroups` 项增加：

```json
{
  "requiredLiteralIdentifiers": ["AIMS-Next", "AIMS"]
}
```

字段按该组原文独立生成。只出现 `AIMS-Next` 时不会机械地再要求一个独立 `AIMS`；同时出现两者时分别要求。任务提示明确要求列表中的每一项必须逐字出现在相同 groupId 的 `translatedText` 中。

### 3.2 保留语法角色，不允许括注扩写

首次真实回放证明，仅要求“可见”仍可能诱导模型输出 `AIMS（圆周理论物理研究所）` 这类错误括注。主提示词和纠正提示因此进一步规定：

- 标识符保持与原文相同的语法、语义角色。
- 不得扩写、定义、括注、重命名或关联到另一个组织。
- `funding for AIMS` 应译为“对 AIMS 的资助”。
- `AIMS-Next Einstein Initiative` 应译为“AIMS-Next 爱因斯坦计划”。

### 3.3 单组纠正重试与精确校验

服务端先解析整批键控结果，再逐组检查金额单位和必要标识符。若只有部分组违反关键约束，只抽取这些组进行一次受限纠正重试，并按 groupId 合并回首次结果；其他已正确翻译的组不会重复生成。纠正后仍不满足约束才返回错误。

错误信息现区分：

- 丢失 `AIMS-Next`；
- 丢失独立的 `AIMS`；
- 丢失金额单位。

这不是放宽校验，而是把“整页失败”改为“问题组自愈一次，最终仍严格验收”。

## 4. 修复后真实输出

同一份 11 个端侧组、26 个 OCR region、899 字符请求经 v3 API 重放后：

| 项目 | 结果 |
| --- | --- |
| 服务端计划 | `AUTHORITATIVE 11→8组` |
| 可翻译组 | 6 |
| 结构性保留组 | 2 |
| 失败组 | 0 |
| 模型 | `qwen3.5-translation:9b` |
| 提示词 | `semantic-translation-qwen-v8-literal-role-preservation` |
| 总耗时 | 65210ms |

主绕图段落：

> 加拿大政府已拨款 2,000 万加元（合 1,940 万美元），在接下来四年内资助非洲数学科学研究所的五个中心。这些中心遍布整个大陆，并通过 AIMS-Next 爱因斯坦计划进行管理。它们将培训有才华的年轻非洲研究生研究人员从事数学科学研究。

下一正文段：

> 对 AIMS 的资助由安大略省的非营利组织圆周理论物理研究所的新全球外展计划推动，该计划由南非出生的科学家尼尔·图罗克博士领导。他也是 AIMS 的创始人和 AIMS-Next 爱因斯坦计划的发起人。

金额、`AIMS`、`AIMS-Next`、组织关系和发起人关系均保留；没有错误括注，也没有跨组内容移动。Admin 页面完整显示服务端语义计划、译文和 renderSlots。

## 5. 验证范围

- Rust：32 个单元测试、8 个 API 测试通过。
- 新增定向测试：丢失标识符时只重试问题组；重试结果按 groupId 合并；独立 `AIMS` 与 `AIMS-Next` 分别判定。
- 真实 Ollama：原保存请求直接复现模型意译；修复后同 OCR 请求通过 v3 API 成功。
- Web：修复后记录为 `SUCCEEDED`，`failedGroupCount=0`，服务端计划和译文布局可见。
- Android：协议响应字段和布局 DSL 未改变，本轮不需要端侧代码修改或重新打包 APK。

**结论：失败属于模型翻译违反关键术语保留约束，原校验正确阻断了坏译文，但错误分类过粗且缺少局部恢复。修复后由“每组显式约束 + 语法角色约束 + 问题组单次纠正重试 + 最终严格校验”共同保证，不再因一个术语漏译让整页直接失败。**
