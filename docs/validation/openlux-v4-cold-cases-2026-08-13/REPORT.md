# OpenLux V4 数据库离散 Case 冷启动翻译验证

- 执行时间：2026-08-13 17:56（Asia/Shanghai）
- 数据库：`demo-server/demo-server.sqlite3`（只读）
- 固定随机种子：`20260813`
- 通过初筛的 V4 历史成功记录：127 条
- 请求总数：95；并发：1；请求间隔：2 秒；429 冷却：10 秒；本轮 429：0
- 原始结果：`results.json`；逐请求数据：`runs.csv`；质量复核：`QUALITY_ASSESSMENT.json`

## Case 设计与抽样方法

1. 从本地 `request_audits` 与 `request_payloads` 联表只读抽样，要求 `status='SUCCEEDED'`、`schemaVersion=4`、请求/响应均为合法 JSON、历史 `failedGroupCount` 为 0。
2. 使用实际 `model_request_json` 中的 `translateGroups`，并要求历史响应结果数量一致、译文非空、必保留字面量已保留。
3. 排除自然语言字符少于 60、最长文本组少于 80 字符的标签型数据。
4. 按源文字符数划分 `80-299 / 300-599 / 600-899 / 900-1149 / >=1150` 五档，在每档内使用固定种子随机抽取；已选文本相似度达到 0.72 的候选会被排除。
5. 5 个 case 同时覆盖 API 文档、OCR 缺字/粘连、技术术语、路径字面量和连续长段落。

| Case | 字符档 | 字符数 | 分组数 | 历史 audit | 内容特征 |
|---|---|---:|---:|---|---|
| `case-1-xs` | 80-299 | 133 | 3 | `05d040bd-baa5-4e9e-a794-8ac950edb19b` | API 文档、路径保持 |
| `case-2-s` | 300-599 | 436 | 4 | `87f7b82f-3ba6-48c9-9cdb-d3c3c04f889f` | Rust 教程、OCR 噪声 |
| `case-3-m` | 600-899 | 888 | 6 | `98c4b39d-6db0-4961-8d31-9456de3d6c56` | 技术介绍、`High-level/low-level` 字面量 |
| `case-4-l` | 900-1149 | 990 | 5 | `cf6036dc-c36b-40dc-af3d-62d0c79580b6` | Cargo、依赖管理、截断文本 |
| `case-5-xl` | >=1150 | 1337 | 5 | `061e7404-8c41-4e0a-819b-926ed432c7fb` | 长篇技术文档、OCR 缺字恢复 |

## 冷启动与验证规则

- 每次请求启动新的 `curl` 进程，强制 HTTP/1.1、`Connection: close`，并发送 `Cache-Control: no-cache, no-store, max-age=0` 与 `Pragma: no-cache`。
- 每次 system prompt 注入唯一 nonce，避免应用层按相同请求内容命中缓存。
- 完整 JSON 质量满分 100：顶层 JSON 对象 20、严格 schema 30、全部 groupId 覆盖 30、必保留字面量 20。只有 HTTP 200 且 JSON 质量 100 才算成功。
- 推理强度覆盖：Gemini `thinkingLevel=minimal/low/medium/high`；Nano/Qwen `reasoning_effort`；GPT 5.6 系列使用 `-low/-medium/-high` 模型后缀。
- 语义质量由人工逐组对照源文复核：准确性 40、完整性 25、技术术语 20、流畅度与 OCR 鲁棒性 15。

## Top 5

综合分权重：成功输出的语义质量 55%、严格交付可靠性 30%、冷启动速度 15%。速度分以本轮最快模型为 100 归一化；无响应由可靠性项单独惩罚，避免在质量项重复扣分。

| 排名 | 模型与档位 | 严格成功 | 中位 TTFT | 中位完整响应 | JSON 中位分 | 语义质量 | 综合分 | 结论 |
|---:|---|---:|---:|---:|---:|---:|---:|---|
| 1 | `gemini-3.1-flash-lite` / minimal | 5/5 | 2089 ms | 3225 ms | 100 | 94.6 | 97.0 | 默认首选，速度与稳定性最佳 |
| 2 | `gpt-5.6-sol` / low | 5/5 | 3736 ms | 5839 ms | 100 | 96.4 | 91.3 | 质量首选，术语和流畅度最佳 |
| 3 | `gpt-5.6-luna` / low | 5/5 | 3829 ms | 5205 ms | 100 | 94.2 | 91.1 | 均衡且稳定，可作为第二路由 |
| 4 | `gpt-5.4-nano` / minimal | 5/5 | 2599 ms | 5335 ms | 100 | 91.6 | 89.5 | 稳定快速，长文本术语稍弱 |
| 5 | `gpt-5.6-terra` / low | 4/5 | 3434 ms* | 5671 ms* | 100 | 95.8* | 85.2 | 成功时质量高，但有一次空响应 |

`*` Terra 延迟中位数仅统计 4 次成功请求；失败请求约 50.95 秒后收到 `curl: (52) Empty reply from server`。

## 推理等级覆盖结果

| 模型 | 控制方式 | 等级 | 严格成功 | 完整响应中位数 | JSON 中位分 | Reasoning tokens 中位数 |
|---|---|---|---:|---:|---:|---:|
| `gemini-3.1-flash-lite` | thinkingLevel | minimal | 5/5 | 3225 ms | 100 | 0 |
| `gemini-3.1-flash-lite` | thinkingLevel | low | 5/5 | 3263 ms | 100 | 0 |
| `gemini-3.1-flash-lite` | thinkingLevel | medium | 5/5 | 2860 ms | 100 | 0 |
| `gemini-3.1-flash-lite` | thinkingLevel | high | 5/5 | 3422 ms | 100 | 0 |
| `gpt-5.4-nano` | reasoning_effort | minimal | 5/5 | 5335 ms | 100 | 0 |
| `gpt-5.4-nano` | reasoning_effort | high | 5/5 | 8173 ms | 100 | 485 |
| `gpt-5.6-terra` | model suffix | low | 4/5 | 5891 ms | 100 | 0 |
| `gpt-5.6-terra` | model suffix | medium | 5/5 | 10685 ms | 100 | 0 |
| `gpt-5.6-terra` | model suffix | high | 5/5 | 9157 ms | 100 | 90 |
| `gpt-5.6-luna` | model suffix | low | 5/5 | 5205 ms | 100 | 58 |
| `gpt-5.6-luna` | model suffix | medium | 5/5 | 10529 ms | 100 | 109 |
| `gpt-5.6-luna` | model suffix | high | 5/5 | 11581 ms | 100 | 375 |
| `gpt-5.6-sol` | model suffix | low | 5/5 | 5839 ms | 100 | 52 |
| `gpt-5.6-sol` | model suffix | medium | 5/5 | 6321 ms | 100 | 55 |
| `gpt-5.6-sol` | model suffix | high | 5/5 | 11398 ms | 100 | 310 |
| `qwen3.5-35b-a3b` | reasoning_effort | minimal | 1/5 | 6565 ms | 40 | - |
| `qwen3.5-35b-a3b` | reasoning_effort | low | 1/5 | 41382 ms | 40 | 4236 |
| `qwen3.5-35b-a3b` | reasoning_effort | medium | 1/5 | 50683 ms | 40 | 5981 |
| `qwen3.5-35b-a3b` | reasoning_effort | high | 2/5 | 49702 ms | 80 | 5647 |

## Qwen 专项结论

`qwen3.5-35b-a3b` 的推理参数确实产生了明显的 reasoning token 与延迟差异，但没有转化为稳定的严格 JSON：四档合计仅 5/20 成功。常见失败是把 `translations` 数组改成对象或直接返回顶层数组；另有一次 `medium` 在 120 秒超时。`low` 仅 1/5 成功，唯一成功请求耗时 41.38 秒，因此不进入 Top 5，也不适合作为短文档默认或回退通道。

## 质量复核摘要

- `gpt-5.6-sol-low` 语义质量最高，Rust 术语处理最稳；适合质量优先通道。
- `gemini-3.1-flash-lite-minimal` 平均只比 Sol 低 1.8 分，但快约 45%，适合默认通道；需要关注将 Rust `ergonomics` 译成“人体工程学”的语境性问题。
- `gpt-5.6-luna-low` 表现最均衡；长 OCR 文本中未主动补齐缺失的附录字母，但没有引入事实性幻觉。
- `gpt-5.4-nano-minimal` 的结构稳定性很好，但在最长 case 将 Rust `lifetimes` 译为“寿命”，技术文档需术语表约束。
- `gpt-5.6-terra-low` 成功输出质量高，但 1/5 无响应使它只能排第五；若上线应配置超时切换。

## 建议路由

默认使用 `gemini-3.1-flash-lite` + `thinkingLevel=minimal`；质量优先或术语密集文本使用 `gpt-5.6-sol-low`；稳定的第二回退使用 `gpt-5.6-luna-low`。`gpt-5.4-nano-minimal` 可用于低成本补充，但建议配 Rust/领域术语表。当前证据不支持用 Qwen 3.5 35B A3B 处理要求严格 JSON 的短文档翻译。
