# Qwen 结构化翻译响应截断修复验证

## 问题记录

- 原审计 ID：`3a028df4-1c88-4b26-a33e-82ee722f6537`
- 请求状态：`FAILED`
- 请求规模：8 个端侧组、32 个 OCR region、1444 个输入字符
- 服务端计划：8 -> 7 个权威组，其中 7 个组需要翻译
- 原错误：`invalid Qwen result JSON: EOF while parsing a string at line 1 column 89`
- 原模型耗时：2678ms

Web 没有错误地隐藏响应；该记录本身没有 `response_json`。保存的实际 Qwen 请求为 13286 bytes，但模型只返回了 89 个字符，因此服务端无法生成翻译响应。

## 根因复现

将记录中的实际送模 JSON 原样发送给 Ollama 后，返回：

```json
{
  "finishReason": "length",
  "contentLength": 89,
  "usage": {
    "prompt_tokens": 4071,
    "completion_tokens": 25,
    "total_tokens": 4096
  }
}
```

本机基础模型 `qwen3.5:9b` 虽然支持 262144 tokens，但 Ollama 实际只为它分配了 4096 上下文。提示词、全文语境、几何数据和 JSON Schema 已占用 4071 tokens，只剩 25 tokens 生成结果，内容在第一个 `groupId` 中途被截断。

因此 EOF 是结果，不是根因。单纯清理 JSON、延长 HTTP 超时或修改 Web 页面都无法修复。

## 修复

### Ollama 运行层

新增 `demo-server/ollama/QwenTranslation.Modelfile`：

```text
FROM qwen3.5:9b
PARAMETER num_ctx 16384
```

创建本地模型别名 `qwen3.5-translation:9b`。复验期间 Ollama 报告其实际 `context_length=16384`，显存占用约 6.03GB。

Ollama 的 OpenAI 兼容接口不能按请求设置 `num_ctx`，因此使用 Modelfile 固定上下文，而不是依赖未定义的请求扩展字段。

### Axum 服务层

- 默认模型改为 `qwen3.5-translation:9b`。
- 新增 `QWEN_MAX_TOKENS`，默认 4096，并写入实际请求和审计数据。
- 解析 OpenAI 兼容响应的 `finish_reason` 与 token usage。
- 当 `finish_reason=length` 时，在 JSON 解析前返回明确的上下文截断错误，包含 prompt/completion/total token 用量。
- 保持整页 7 组一次送模，没有为了绕开问题拆成逐段翻译，因此全文感知职责不变。

## 同请求修复验证

直接送模复验：

```json
{
  "model": "qwen3.5-translation:9b",
  "finishReason": "stop",
  "contentLength": 1211,
  "resultCount": 7,
  "usage": {
    "prompt_tokens": 4071,
    "completion_tokens": 436,
    "total_tokens": 4507
  }
}
```

通过 Axum v3 API 原样重放后的新记录：

- 审计 ID：`a44e4c67-26ad-44c5-bc0b-ebdca9c287fb`
- 请求 ID：`f61400a1-7ccc-4f22-8d13-0a6a86ba4e31`
- 状态：`SUCCEEDED`
- 模型：`qwen3.5-translation:9b`
- 耗时：27888ms
- 结果：7 个 `TRANSLATED`、0 个 `PRESERVED`、0 个 `FAILED`
- 模型请求审计：13316 bytes，确认包含 `max_tokens=4096`

Admin 页面确认无错误带，服务计划为 `AUTHORITATIVE 8→7组`，译文面板完整显示 7 个组，实际送模区域默认折叠，页面无横向溢出和控制台错误。

新记录：<http://192.168.0.63:8090/admin/requests/a44e4c67-26ad-44c5-bc0b-ebdca9c287fb>

## 剩余质量边界

最后两行 OCR 已被底部悬浮控件污染为 `renrA` 和 `syste PrivaLy`。修复后的模型能够完成结构化响应，但对污染文本增加了括号解释；这属于 OCR 遮挡与短残句保护问题，不是上下文截断。后续应通过端侧遮挡区域排除或服务端低置信保护处理，不能通过缩小上下文窗口解决。

## 验证结果

- Rust：15 个单元测试、7 个 API 测试通过。
- 新增测试确认 `finish_reason=length` 优先报告截断，不再误报 JSON EOF。
- `cargo fmt --all -- --check` 通过。
- `git diff --check` 通过。
- 修复后 Admin 截图：`admin-after.png`。

## 结论

本次异常属于 Ollama 实际上下文配置不足。16K 翻译模型别名解决了完整响应问题，服务端的结束原因检查则补齐了可诊断性。该请求从 89 字符的截断 JSON 恢复为 7/7 组完整翻译，现有整页感知和组级回贴协议无需调整。
