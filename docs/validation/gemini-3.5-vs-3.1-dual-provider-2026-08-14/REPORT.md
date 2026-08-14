# Gemini 3.5/3.1 Flash-Lite 双 Provider thinking 复测

- 执行时间：2026-08-14 14:12–14:55（Asia/Shanghai）
- Provider：Google Native `generateContent`、OpenLux `/chat/completions`
- 模型：`gemini-3.5-flash-lite`、`gemini-3.1-flash-lite`
- thinking 状态：完全省略、`minimal`、`low`、`medium`、`high`
- 代理：`http://127.0.0.1:7897`
- 并发：1；请求间隔：2 秒；429 冷却：10 秒；本轮 429：0
- 请求：300；严格成功：284；真实墙钟：2586.7 秒（43 分 6.7 秒）
- Google Native：138/150；纯请求时间 619.7 秒
- OpenLux：146/150；纯请求时间 1357.8 秒

## 测试设计

覆盖模式对每个 Provider/模型/thinking 状态分别运行 XS/S/M/L/XL 五个 V4 case，共 `2 × 2 × 5 × 5 = 100` 次。连续模式固定使用 888 字符、6 分组的 M case，对每个 Provider/模型/thinking 状态连续运行 10 次，共 `2 × 2 × 5 × 10 = 200` 次。

每次请求均为新 `curl` 进程、新连接、唯一 nonce，并发送 no-cache 与 `Connection: close`。Google Native 的显式参数为 `generationConfig.thinkingConfig.thinkingLevel`；OpenLux 为 `google.thinking_config.thinking_level`。`omitted` 请求体中完全没有对应 thinking 对象或字段。

严格成功要求 HTTP 200，JSON 顶层/schema 正确，全部 groupId 无重复且完整覆盖，必保留字面量存在。HTTP 200 但内容被截断、出现乱码或漏掉 `High-level/low-level` 仍记失败。

## 覆盖模式

| Provider | 模型 | thinking | 成功 | 响应中位数 | P90 | JSON 中位分 | 参考相似度中位数 | thoughts 中位数 |
|---|---|---|---:|---:|---:|---:|---:|---:|
| Google | 3.5 | omitted | 5/5 | 3110 ms | 4023 ms | 100 | 83.5 | - |
| Google | 3.5 | minimal | 4/5 | 3480 ms | 7694 ms | 100 | 83.5 | - |
| Google | 3.5 | low | 5/5 | 2806 ms | 2831 ms | 100 | 81.4 | - |
| Google | 3.5 | medium | 5/5 | 2677 ms | 3364 ms | 100 | 83.7 | - |
| Google | 3.5 | high | 5/5 | 4320 ms | 15926 ms | 100 | 83.5 | 1592 |
| Google | 3.1 | omitted | 5/5 | 3133 ms | 3640 ms | 100 | 81.2 | - |
| Google | 3.1 | minimal | 5/5 | 2973 ms | 3443 ms | 100 | 81.7 | - |
| Google | 3.1 | low | 5/5 | 5374 ms | 11235 ms | 100 | 79.5 | 103 |
| Google | 3.1 | medium | 5/5 | 2994 ms | 3249 ms | 100 | 80.1 | - |
| Google | 3.1 | high | 3/5 | 8191 ms | 12669 ms | 100 | 81.1 | 1333 |
| OpenLux | 3.5 | omitted | 3/5 | 9152 ms | 42104 ms | 100 | 89.7 | 1286 |
| OpenLux | 3.5 | minimal | 5/5 | 8182 ms | 9947 ms | 100 | 86.8 | 0 |
| OpenLux | 3.5 | low | 5/5 | 8328 ms | 11657 ms | 100 | 86.8 | 0 |
| OpenLux | 3.5 | medium | 5/5 | 6545 ms | 13060 ms | 100 | 90.0 | 0 |
| OpenLux | 3.5 | high | 5/5 | 7302 ms | 8984 ms | 100 | 90.7 | 0 |
| OpenLux | 3.1 | omitted | 5/5 | 9412 ms | 9718 ms | 100 | 81.9 | 0 |
| OpenLux | 3.1 | minimal | 5/5 | 9686 ms | 10310 ms | 100 | 81.0 | 0 |
| OpenLux | 3.1 | low | 5/5 | 9344 ms | 10690 ms | 100 | 81.1 | 0 |
| OpenLux | 3.1 | medium | 5/5 | 9464 ms | 10387 ms | 100 | 81.0 | 0 |
| OpenLux | 3.1 | high | 5/5 | 9548 ms | 10087 ms | 100 | 81.3 | 0 |

## 连续 10 次

“请求总时间”是 10 次接口 `total_ms` 之和；“限速墙钟”再加 9 个两秒间隔，不包含脚本的少量序列化开销。

| Provider | 模型 | thinking | 成功 | 请求总时间 | 平均/中位 | 限速墙钟 | JSON 中位分 | thoughts 总数 |
|---|---|---|---:|---:|---:|---:|---:|---:|
| Google | 3.5 | omitted | 9/10 | 31.75 s | 3.18/3.06 s | 49.75 s | 100 | 0 |
| Google | 3.5 | minimal | 9/10 | 32.70 s | 3.27/3.19 s | 50.70 s | 100 | 0 |
| Google | 3.5 | low | 9/10 | 31.05 s | 3.11/3.01 s | 49.05 s | 100 | 0 |
| Google | 3.5 | medium | 10/10 | 31.01 s | 3.10/3.00 s | 49.01 s | 100 | 0 |
| Google | 3.5 | high | 10/10 | 33.55 s | 3.35/2.87 s | 51.55 s | 100 | 0 |
| Google | 3.1 | omitted | 10/10 | 33.37 s | 3.34/3.18 s | 51.37 s | 100 | 0 |
| Google | 3.1 | minimal | 10/10 | 35.73 s | 3.57/3.16 s | 53.73 s | 100 | 0 |
| Google | 3.1 | low | 10/10 | 47.60 s | 4.76/4.81 s | 65.60 s | 100 | 828 |
| Google | 3.1 | medium | 10/10 | 29.17 s | 2.92/2.85 s | 47.17 s | 100 | 0 |
| Google | 3.1 | high | 4/10 | 88.93 s | 8.89/9.34 s | 106.93 s | 0 | 3625* |
| OpenLux | 3.5 | omitted | 9/10 | 93.72 s | 9.37/7.30 s | 111.72 s | 100 | 0 |
| OpenLux | 3.5 | minimal | 10/10 | 86.53 s | 8.65/8.06 s | 104.53 s | 100 | 0 |
| OpenLux | 3.5 | low | 10/10 | 73.94 s | 7.39/7.61 s | 91.94 s | 100 | 0 |
| OpenLux | 3.5 | medium | 10/10 | 73.95 s | 7.39/7.47 s | 91.95 s | 100 | 0 |
| OpenLux | 3.5 | high | 10/10 | 72.68 s | 7.27/6.90 s | 90.68 s | 100 | 0 |
| OpenLux | 3.1 | omitted | 10/10 | 94.61 s | 9.46/9.42 s | 112.61 s | 100 | 0 |
| OpenLux | 3.1 | minimal | 10/10 | 95.74 s | 9.57/9.59 s | 113.74 s | 100 | 0 |
| OpenLux | 3.1 | low | 10/10 | 94.90 s | 9.49/9.49 s | 112.90 s | 100 | 0 |
| OpenLux | 3.1 | medium | 10/10 | 100.14 s | 10.01/9.36 s | 118.14 s | 100 | 0 |
| OpenLux | 3.1 | high | 9/10 | 97.82 s | 9.78/9.74 s | 115.82 s | 100 | 0 |

`*` Google 3.1 high 的 thoughts 总数只统计 4 次严格成功；六次失败另消耗了大量 thought tokens。15 次合并共 8538 个成功请求 thoughts，但只成功 7/15。

## 不传 thinking 的效果

| Provider / 模型 | omitted 合并成功 | 对照 | 判断 |
|---|---:|---|---|
| Google / 3.5 | 14/15 | medium 15/15 | 可用但不应默认；出现字面量遗漏 |
| Google / 3.1 | 15/15 | medium 15/15 | 稳定可用；medium 连续总时间更短 |
| OpenLux / 3.5 | 12/15 | medium 15/15 | 不可默认；覆盖阶段触发默认推理并截断，连续阶段另有连接失败 |
| OpenLux / 3.1 | 15/15 | minimal/low/medium 均 15/15 | 可用；显式档位没有可观测收益 |

OpenLux 3.5 omitted 在覆盖阶段多次产生约 1000–1500 reasoning tokens，并发生响应截断；连续阶段 usage 又全部显示 0，说明默认路由或 usage 计数不稳定。OpenLux 3.1 的五种状态几乎都为 0 reasoning tokens、耗时相近，参数被接受不等于等级真实生效。

## 翻译质量

严格成功的译文总体可用，3.5 的历史参考相似度通常高于 3.1，但该指标只衡量措辞接近程度，不等同人工准确率。人工抽查 M case 时，OpenLux 3.5 多次把 Rust `ergonomics` 处理为“易用性”，比两条链路中常见的“人体工程学”更符合编程语言语境；两模型都能在正常输出时保留 `High-level/low-level`。

失败不是单纯 Markdown 包裹：Google 3.5 的 omitted/low 各出现一次完整 schema 但漏必保留字面量，minimal 出现一次异常乱码；Google 3.1 high 多次把输出预算消耗在 thoughts 后返回截断 JSON。严格验证不可放宽。

## 结论与参数

### 主模型

Google Native `gemini-3.5-flash-lite`，显式 `thinkingLevel=MEDIUM`：覆盖 5/5、连续 10/10，合计 15/15；连续纯请求总时间 31.01 秒。它比 3.1 的译文风格更接近历史成功参考，同时没有 high 的长尾和输出预算风险。

```json
{
  "provider": "google",
  "model": "gemini-3.5-flash-lite",
  "timeoutSeconds": 8,
  "generationConfig": {
    "temperature": 0,
    "thinkingConfig": {
      "includeThoughts": true,
      "thinkingLevel": "MEDIUM"
    },
    "responseMimeType": "application/json",
    "responseJsonSchema": "V4 strict translation schema",
    "maxOutputTokens": "按源文和分组动态计算"
  }
}
```

### 备选顺序

1. OpenLux `gemini-3.5-flash-lite` + 显式 `thinking_level=medium`：15/15，连续总时间 73.95 秒。作为 Google 原生链路故障时的 Provider 备选。
2. Google Native `gemini-3.1-flash-lite` + `thinkingLevel=MEDIUM`：15/15，连续总时间 29.17 秒。速度略快，但人工与参考一致性不优于 3.5；适合 3.5 模型不可用时。
3. OpenLux `gemini-3.1-flash-lite` + `thinking_level` 省略或 `minimal`：两者均 15/15，连续总时间约 94.6–95.7 秒。建议生产仍显式 minimal，避免未来网关默认值变化。

禁止将 Google Native 3.1 `HIGH` 用于当前输出预算：合并仅 7/15 严格成功。3.5 `HIGH` 虽为 15/15，但覆盖 P90 15.93 秒且连续质量代理无稳定提升，不作为默认档。对两个 3.5 路径均不要省略 thinking 参数。

本轮结果将原报告的 `3.5/MINIMAL` 主参数更新为 `3.5/MEDIUM`。该调整来自 15 次/配置的新样本，不是基于单次最快值。
