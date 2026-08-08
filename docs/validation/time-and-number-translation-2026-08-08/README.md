# 时间与数字混合文本翻译修复验证（2026-08-08）

## 1. 问题结论

本次不是 Qwen 不翻译时间或数字，而是文本在送模前已被端侧标记为 `PRESERVED`，服务端又按旧角色直接保护，导致整组没有进入 Qwen 输入。

真实审计记录 `6f8414fc-ebf8-4bcc-95f8-312c375b9064` 中：

```text
role=METADATA
translationUnit=PRESERVED
sourceText=City,Vietnam,Aug.4,2026.Chinese film"Dear You"
result.status=PRESERVED
```

数据库还存在以下同类误判：

- `The March ended in 1956 but,`
- `Center in Florida on Jan.17,2026,ahead of the historic Artemis`
- `Province,Aug.7,2026.(Photo by Quan`

端侧旧 `AUTHOR_DATE` 规则只要求文本任意位置出现月份和年份，因此普通正文也会被标为 `METADATA`。另一条旧规则以数字占比判断编号，`At 17:27` 这类短语中 4 个数字占比超过 65%，也可能在进入翻译前被保护。

## 2. 修复后的判定矩阵

| 输入类型 | 示例 | 处理 |
| --- | --- | --- |
| 纯时间 | `22:43`、`17:27 PM` | 保留 |
| 纯日期 | `2026-08-04`、`06 August 2026` | 保留 |
| 作者与日期 | `Ngotho Gichuru and Byaruhanga Rukooko 06 August 2026` | 保留 |
| 纯编号 | `M26-061`、`W3000 t5`、`4G` | 保留 |
| 时间位于句中 | `At 17:27 the meeting started` | 翻译 |
| 日期位于句中 | `The March ended in 1956 but...` | 翻译 |
| 版本数字 | `Version 3 is ready` | 翻译并保留数字语义 |
| 金额与单位 | `Awarded C$20 million` | 翻译并保留金额 |
| 中文序号 | `第3章已经发布` | 翻译并保留数字 |

作者日期不再仅依赖月份与年份。日期必须位于整行尾部，日期前必须符合姓名词结构；姓名词首字母应为大写，并允许 `and/van/de/von` 等姓名连接词。普通句子即使以日期结尾，只要前缀是自然语言句子，也不会被当作作者栏。

## 3. 端侧改造

新增统一的 `SemanticContentClassifier`：

1. OCR 角色分类仅将完整纯时间识别为 `TIMESTAMP`。
2. 只有完整发送者或作者日期结构识别为 `METADATA`。
3. 协议映射不再根据角色名称无条件生成 `PRESERVED`，而是结合完整文本重新判断。
4. 数字标识符要求不存在两个及以上连续拉丁字母；因此 `M26-061` 保留，`Version 3`、`At 17:27` 进入翻译。
5. 批量翻译、语义组翻译和本地翻译共用同一套内容分类，避免入口之间行为不一致。

## 4. 服务端兼容

服务端不再直接信任旧客户端上报的 `translationUnit=PRESERVED`。对于 `TIMESTAMP/METADATA` 但包含可翻译句子的旧请求，服务端会在构建文档计划前规范化为：

```json
{
  "role": "BODY",
  "translationUnit": "GROUP",
  "groupingEvidence": ["SERVER_TRANSLATION_ELIGIBILITY_OVERRIDE"]
}
```

纯时间、作者日期、URL、电话、代码、控件和纯编号继续保护。规范化发生在文档计划之前，因此 Admin 的服务端计划、实际 Qwen 输入和最终结果保持一致。

## 5. 真实请求与输出

使用原记录中的目标 OCR region 构造最小 v3 重放请求：

```json
{
  "requestId": "time-number-targeted-replay-20260808",
  "group": {
    "groupId": "semantic-76c880d0",
    "role": "METADATA",
    "translationUnit": "PRESERVED",
    "sourceText": "City,Vietnam,Aug.4,2026.Chinese film\"Dear You\""
  }
}
```

实际服务端计划与 Qwen 输出：

```json
{
  "plan": {
    "role": "BODY",
    "translationUnit": "GROUP",
    "groupingEvidence": ["SERVER_TRANSLATION_ELIGIBILITY_OVERRIDE"]
  },
  "result": {
    "status": "TRANSLATED",
    "translatedText": "越南，城市，2026年8月4日。中国电影《致你》"
  },
  "metrics": {
    "groupCount": 1,
    "translatedGroupCount": 1,
    "preservedGroupCount": 0,
    "failedGroupCount": 0,
    "totalMs": 9713
  }
}
```

第一次整页重放也成功翻译该组，译文为 `城市，越南，2026年8月4日。电影《致你》`，13/13 组均为 `TRANSLATED`。规范化计划后的第二次整页重放因 Qwen 返回重复 `groupId` 被严格校验拒绝；该错误属于模型整页结构化输出的独立非确定性问题。随后执行的单组定向重放成功，证明本次时间/数字修复有效，不能把整页重复 ID 错误归因于分类逻辑。

## 6. 自动验证

- Android Debug JVM：194 项通过，0 失败。
- Android 主 APK 与 AndroidTest APK：构建成功。
- Rust：27 项单元测试、8 项 API 测试通过。
- API 用例覆盖旧客户端 `METADATA/PRESERVED` 混合句在 v3 中规范化为 `BODY/GROUP/TRANSLATED`。
- Debug APK SHA-256：`e3af98efa1740812380552e0bffb74e4cabeea8f72a4c39d124f9a2269b3551e`。

**结论：时间、日期和数字本身不是“不翻译”信号。只有整个语义组本质上是时间、作者日期或编号时才保护；只要其中包含可翻译自然语言，就必须整组送入翻译，并保留数字、时间、金额和单位语义。**

对应原始记录：`http://192.168.1.4:8090/admin/requests/6f8414fc-ebf8-4bcc-95f8-312c375b9064`
