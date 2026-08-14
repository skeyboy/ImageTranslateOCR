# Google 原生 Gemini V4 Case 代理冷启动验证

> 2026-08-14 更新：文末新增 300 次双 Provider/双模型复测。最新主参数为 Google Native `gemini-3.5-flash-lite + MEDIUM`，覆盖此前基于 5 次样本提出的 `MINIMAL` 建议。

- 执行时间：2026-08-13（Asia/Shanghai）
- 链路：Google Gemini 原生 `generateContent`
- Base URL：`https://generativelanguage.googleapis.com/v1beta`
- Endpoint：`https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent`
- 模型：`gemini-3.5-flash-lite`
- 代理：`http://127.0.0.1:7897`
- 样本：复用 V4 数据库的 5 个离散 case，源文长度 133、436、888、990、1337 字符
- 请求：20 次，单线程串行，每次间隔 2 秒；20/20 为 HTTP 200，429 为 0

## 测试方法

这次不经过 OpenLux `/v1/chat/completions`。请求直接发送到 Google 原生链接，使用 `x-goog-api-key` 鉴权，并明确通过本地 HTTP 代理。

每个请求使用新的 `curl` 进程和 TCP 连接，强制 HTTP/1.1、`Connection: close`，发送 `Cache-Control: no-cache, no-store, max-age=0` 与 `Pragma: no-cache`，并在 system prompt 中加入唯一 nonce。Google 原生 `generateContent` 是非流式调用，因此只记录 header 和完整响应时间，不报告不可观测的 TTFT。

原生请求中的推理参数为：

```json
{
  "generationConfig": {
    "thinkingConfig": {
      "includeThoughts": true,
      "thinkingLevel": "MINIMAL|LOW|MEDIUM|HIGH"
    }
  }
}
```

结构验证满分 100：顶层 JSON 对象 20、严格 schema 30、全部 groupId 覆盖 30、必保留字面量 20。只有 HTTP 200 且结构分 100 才算成功。

## thinkingLevel 结果

| thinkingLevel | 严格成功 | 完整响应中位数 | JSON 中位分 | 可观测 thought tokens |
|---|---:|---:|---:|---:|
| `minimal` | 5/5 | 2528 ms | 100 | 0/5 请求暴露 |
| `low` | 5/5 | 2398 ms | 100 | 0/5 请求暴露 |
| `medium` | 5/5 | 2508 ms | 100 | 0/5 请求暴露 |
| `high` | 5/5 | 2439 ms | 100 | 1/5 请求暴露；2049 tokens |

四档合计 20/20 严格成功。前三个档位以及多数 `high` 请求没有返回 `thoughtsTokenCount`，耗时也接近，因此不能仅凭参数被接受就断言每次均增加推理。XL case 的 `high` 是清晰的生效证据：2049 thought tokens，完整响应 10144 ms；同 case 的 `minimal/low/medium` 分别为 3170/3011/2776 ms。

## 按长度结果

| Case | 字符数 | minimal | low | medium | high | 四档 JSON |
|---|---:|---:|---:|---:|---:|---:|
| XS | 133 | 1964 ms | 1784 ms | 1701 ms | 1767 ms | 4/4 × 100 |
| S | 436 | 2001 ms | 2106 ms | 2071 ms | 2082 ms | 4/4 × 100 |
| M | 888 | 2667 ms | 2561 ms | 2508 ms | 2547 ms | 4/4 × 100 |
| L | 990 | 2528 ms | 2398 ms | 2745 ms | 2439 ms | 4/4 × 100 |
| XL | 1337 | 3170 ms | 3011 ms | 2776 ms | 10144 ms | 4/4 × 100 |

## 翻译质量

`minimal` 的五档人工源文对照约为 95/100，分组完整、路径与 `High-level/low-level` 字面量均保留。主要问题是把 Rust 文档中的 `ergonomics` 译为“人机工程学”，语境上更适合“易用性/人体工学设计”；其余 Cargo、依赖、生命周期等技术内容准确。

XL 的 `high` 相比 `minimal` 更保守：没有根据上下文擅自补齐 OCR 丢失的附录字母，并将 `nightly Rust` 保留为 `nightly 版 Rust`；质量略好，但耗时增加到约 10.1 秒。对短文档默认路径，这点增益不足以抵消延迟。

## 结论

Google 原生链接经代理的表现明显优于上一轮同名模型通过 OpenLux 兼容接口时的结构表现：本轮 20/20 严格 JSON 成功。推荐默认使用 `gemini-3.5-flash-lite` + `thinkingLevel=minimal`；只有术语密集、OCR 损坏较重且允许更高尾延迟时才考虑 `high`。档位不是稳定的“越高越慢”，而是可能按内容触发额外思考。

原始结果见 `results.json`，逐请求数据见 `runs.csv`，执行脚本为 `scripts/google_gemini_v4_cold_case_benchmark.py`。

## 当前场景的综合选型

本节结合 Google 原生链路 20 次请求和 OpenLux 链路 95 次请求，作为当前短文档 OCR 英译中、严格 JSON 返回场景的最终建议。不同链路的延迟不直接拼成一个样本集；先分别判断链路稳定性，再决定跨供应商回退顺序。

### 推荐模型链

| 角色 | Provider / 模型 | 推理参数 | 单次超时 | 实测依据 | 使用目的 |
|---|---|---|---:|---|---|
| 主模型 | Google Native / `gemini-3.5-flash-lite` | `thinkingLevel=MINIMAL` | 6 秒 | 5/5；中位 2528 ms；JSON 100；语义约 95 | 默认短文档翻译 |
| 一级备选 | OpenLux / `gpt-5.6-sol-low` | 模型后缀 `-low` | 12 秒 | 5/5；中位 5839 ms；质量 96.4，五个候选最高 | 主链路失败或术语质量优先 |
| 二级备选 | OpenLux / `gpt-5.6-luna-low` | 模型后缀 `-low` | 15 秒 | 5/5；中位 5205 ms；质量 94.2 | Sol 不可用时的独立稳定回退 |
| 应急快速备选 | OpenLux / `gpt-5.4-nano` | `reasoning_effort=minimal` | 12 秒 | 5/5；中位 5335 ms；质量 91.6 | 容量、成本或前两级同时异常 |
| 质量升级档 | Google Native / `gemini-3.5-flash-lite` | `thinkingLevel=HIGH` | 15 秒 | 5/5；XL case 2049 thoughts、10144 ms | OCR 严重损坏且允许高尾延迟 |

不建议把 `gpt-5.6-terra-low` 放入自动主链：虽然成功输出质量为 95.8，但发生过一次约 50.95 秒后的空响应。`qwen3.5-35b-a3b` 四档只有 5/20 严格成功，`low` 仅 1/5，因此从自动回退链排除。`gemini-3.1-flash-lite-minimal` 在 OpenLux 上速度较快且 5/5 成功，但与主模型同属 Gemini 家族，不提供足够的供应商故障隔离，可作为人工启用的同族备用，不作为一级自动回退。

### 主模型参数

```json
{
  "provider": "google",
  "baseUrl": "https://generativelanguage.googleapis.com/v1beta",
  "model": "gemini-3.5-flash-lite",
  "proxy": "http://127.0.0.1:7897",
  "timeoutSeconds": 6,
  "generationConfig": {
    "temperature": 0,
    "thinkingConfig": {
      "includeThoughts": true,
      "thinkingLevel": "MINIMAL"
    },
    "responseMimeType": "application/json",
    "responseJsonSchema": "V4 translation strict schema",
    "maxOutputTokens": "按分组数和源文长度动态计算"
  }
}
```

生产请求必须继续验证：HTTP 200、顶层 JSON 对象、`translations` 数组、字段集合严格匹配、groupId 无重复且全覆盖、`targetLanguage` 正确、必保留字面量完整。HTTP 200 但结构不合格仍视为失败并立即进入下一级，不能尝试宽松解析后直接交付。

### 路由规则

1. 普通请求先调用 Google Native `gemini-3.5-flash-lite/MINIMAL`。6 秒内成功并通过严格验证则直接返回。
2. 网络错误、超时、429、5xx、空响应或 JSON/groupId 校验失败时，不在原链路连续重试，直接切换 `gpt-5.6-sol-low`，避免放大尾延迟。
3. Sol 在 12 秒内失败时切换 `gpt-5.6-luna-low`；Luna 失败后才使用 `gpt-5.4-nano/minimal` 应急。
4. 对源文不少于约 1200 字符，同时存在明显 OCR 缺字/粘连，并且业务允许约 10-15 秒延迟的质量优先请求，可直接使用 Google `HIGH`。若只是技术术语密集，优先路由 Sol-low，比无条件开启 HIGH 更稳定。
5. 每次回退都复用原始 V4 groupId、schema、字面量约束和目标语言；记录 provider、实际模型、推理档位、超时原因、JSON 校验结果与耗时，便于后续按真实流量调整阈值。

### 最终结论

当前最合理的生产组合是：Google 原生 `gemini-3.5-flash-lite + MINIMAL` 做主翻译模型，`gpt-5.6-sol-low` 做质量型一级备选，`gpt-5.6-luna-low` 做稳定二级备选，`gpt-5.4-nano + minimal` 仅作应急容量补充。该组合兼顾约 2.5 秒的主路径响应、严格 JSON 完整性、技术翻译质量和跨模型故障隔离。

---

## 2026-08-14 双模型、双 Provider 复测

本节为当前最新结论，覆盖上方基于较小样本的 `MINIMAL` 主参数建议。完整报告和 300 条逐请求结果位于 `docs/validation/gemini-3.5-vs-3.1-dual-provider-2026-08-14/`。

### 范围

- Google Native 与 OpenLux 各 150 次，共 300 次；并发 1、间隔 2 秒、429 为 0。
- 模型为 `gemini-3.5-flash-lite`、`gemini-3.1-flash-lite`。
- thinking 状态为完全省略、minimal、low、medium、high。
- 覆盖模式：每个组合运行 5 个离散长度 case，共 100 次。
- 连续模式：每个组合在 888 字符 M case 连续运行 10 次，共 200 次。
- 总严格成功 284/300；完整墙钟 43 分 6.7 秒。

### 合并稳定性

| Provider | 模型 | omitted | minimal | low | medium | high |
|---|---|---:|---:|---:|---:|---:|
| Google | 3.5 | 14/15 | 13/15 | 14/15 | **15/15** | 15/15 |
| Google | 3.1 | 15/15 | 15/15 | 15/15 | **15/15** | 7/15 |
| OpenLux | 3.5 | 12/15 | 15/15 | 15/15 | **15/15** | 15/15 |
| OpenLux | 3.1 | 15/15 | 15/15 | 15/15 | 15/15 | 14/15 |

### 连续 10 次总时间

| Provider | 模型 | omitted | minimal | low | medium | high |
|---|---|---:|---:|---:|---:|---:|
| Google | 3.5 | 31.75 s | 32.70 s | 31.05 s | **31.01 s** | 33.55 s |
| Google | 3.1 | 33.37 s | 35.73 s | 47.60 s | **29.17 s** | 88.93 s |
| OpenLux | 3.5 | 93.72 s | 86.53 s | 73.94 s | **73.95 s** | 72.68 s |
| OpenLux | 3.1 | 94.61 s | 95.74 s | 94.90 s | 100.14 s | 97.82 s |

以上为纯请求时间之和；每组包含 9 个两秒限速间隔时再加 18 秒。成功率必须与总时间一起看，例如 Google 3.1 high 只有 4/10，不能因为部分响应结束较快而采用。

### 不传 thinking

- Google 3.5 omitted 合并 14/15，低于 medium 的 15/15；不建议省略。
- Google 3.1 omitted 为 15/15，稳定可用，但 medium 连续总时间更短。
- OpenLux 3.5 omitted 仅 12/15；覆盖阶段曾触发 1000–1500 reasoning tokens 并截断，连续阶段又出现连接失败，必须显式传档位。
- OpenLux 3.1 omitted 为 15/15，与显式档位耗时接近且都报告 0 reasoning tokens；可以使用，但生产建议显式 minimal 以固定语义。

### 最新结论

主模型更新为 Google Native `gemini-3.5-flash-lite + thinkingLevel=MEDIUM`，`temperature=0`、V4 严格 schema、动态 `maxOutputTokens`、建议单次超时 8 秒。该组合覆盖 5/5、连续 10/10，合计 15/15；连续纯请求总时间 31.01 秒。

Gemini 范围内的备选顺序：

1. OpenLux `gemini-3.5-flash-lite` + 显式 medium：15/15，连续 73.95 秒。
2. Google Native `gemini-3.1-flash-lite` + medium：15/15，连续 29.17 秒；速度快但译文一致性指标低于 3.5。
3. OpenLux `gemini-3.1-flash-lite` + 显式 minimal：15/15，连续 95.74 秒。

Google Native 3.1 high 合并仅 7/15，原因是大量输出预算被 thoughts 消耗后 JSON 截断，禁止用于当前配置。3.5 high 虽然 15/15，但覆盖 P90 达 15.93 秒且没有稳定质量增益，也不作为默认档。

---

## GPT-5.3 Codex Spark 再探测（2026-08-14 15:49）

按相同 V4 M case、代理和冷启动方式，对 `gpt-5.3-codex-spark` 补做 Google 原生与 OpenLux 恢复探测。完整结果位于 `docs/validation/gpt-5.3-codex-spark-retry-2026-08-14/`。

Google 原生实际请求 `https://generativelanguage.googleapis.com/v1beta/models/gpt-5.3-codex-spark:generateContent`，返回 HTTP 404：模型不存在或不支持 `generateContent`。该模型属于 OpenAI Provider，无法通过 Gemini 原生接口运行；因此不能产生 Google 直连速度、质量或 thinkingLevel 对比数据。

OpenLux `/responses` 完全省略 reasoning 参数后，以 10 秒间隔探测三次，结果分别为 2521、2510、2430 ms，三次均 HTTP 429，错误都是“当前分组上游负载已饱和，请稍后再试”。结合稍早 `/chat/completions` 与 `/responses` 的相同结果，确认渠道仍未恢复，而不是 endpoint 或推理参数问题。

恢复门槛要求连续三次 HTTP 200 且严格 JSON 成功，本次为 0/3，因此没有继续执行 75 次覆盖/十连测试。`gpt-5.3-codex-spark` 继续标记为“目录存在、当前上游不可用”，不能进入主模型或备选链。
