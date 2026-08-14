# GPT-5.3 Codex Spark 恢复探测

- 执行时间：2026-08-14 15:49（Asia/Shanghai）
- 模型：`gpt-5.3-codex-spark`
- Case：888 字符、6 分组的 V4 M case
- 代理：`http://127.0.0.1:7897`
- thinking：完全省略
- 并发：1；OpenLux 探针间隔：10 秒

## Google 原生直连验证

实际请求：

`https://generativelanguage.googleapis.com/v1beta/models/gpt-5.3-codex-spark:generateContent`

结果为 HTTP 404。Google 返回：该模型在 Gemini API `v1beta` 中不存在，或不支持 `generateContent`。这是 Provider/模型不匹配，不是代理、thinking 参数或翻译请求格式问题。

`gpt-5.3-codex-spark` 是 OpenAI 模型，不能通过 Google Gemini 原生接口运行。因此不存在可供比较的 Google 直连翻译质量、响应速度或 thinkingLevel 数据。

## OpenLux 恢复探针

OpenLux 市场目录声明该模型支持 OpenAI Chat Completions 和 Responses。本次使用 `/responses`，完全省略 reasoning 参数，连续低频探测三次：

| 次数 | HTTP | 耗时 | 严格成功 | 错误 |
|---:|---:|---:|---:|---|
| 1 | 429 | 2521 ms | 否 | 当前分组上游负载已饱和，请稍后再试 |
| 2 | 429 | 2510 ms | 否 | 当前分组上游负载已饱和，请稍后再试 |
| 3 | 429 | 2430 ms | 否 | 当前分组上游负载已饱和，请稍后再试 |

今天稍早的探测中，OpenLux `/chat/completions` 与 `/responses` 也都返回相同 429。本次三次结果证明上游仍未恢复，而不是单个 endpoint 不兼容。

## 结论

- Google 原生：确定不可调用，HTTP 404；无需继续测试。
- OpenLux：当前可用率 0/3，且与稍早 9 次 429 结果一致；不能评估实际翻译速度、质量、JSON 或 reasoning 档位。
- 按既定限频策略，不启动 25 次覆盖和 50 次连续测试，避免继续请求已饱和上游。
- 该模型继续标记为“目录存在、当前上游不可用”，不能进入主模型或备选链。
- 后续只有 omitted 单次探针连续三次 HTTP 200 且严格 JSON 成功后，才值得恢复 75 次完整测试。

原始探针见 `probes.json`；执行脚本为 `scripts/gpt53_codex_spark_recovery_probe.py`。
