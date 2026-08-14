# OpenLux 前 10 页模型短文档 JSON 翻译验证

- 生成时间：2026-08-13T09:00:53.666911+00:00
- 市场模型总数：401
- 参照模型：`gemini-3.5-flash-lite`
- 参数被接口接受不代表推理等级实际生效；需要严格 JSON 成功及耗时或 reasoning token 差异共同证明。

- 输入附件：`/Users/lee/.codex/attachments/dc1d1eb3-edc4-4968-be30-f122e5863497/pasted-text.txt`
- 输入分组：7；每条译文必需字段：groupId, translatedText, detectedSourceLanguage, targetLanguage
- 访问限制：单线程串行，每次请求间隔 2.0 秒，429 后冷却 10.0 秒

## 结果摘要

- `gemini-3.1-flash-lite` 是本轮严格 JSON 最快的模型，thinkingLevel 四档全部达到 100 分；但四档延迟不单调，等级效果未证实。
- `gpt-5.4-nano` 是推理强度可调性最清晰的低延迟候选：minimal 保持严格 JSON，high 会显著增加耗时和 reasoning tokens。
- `qwen3.5-35b-a3b` 的 high 档推理最重，适合质量优先的按需通道，不适合短文档默认路径。
- `gemini-3.5-flash-lite` 与 `gemini-3.6-flash` 的译文内容大多存在，但被 Markdown 围栏或分析文字包裹，不能直接转换为附件要求的 JSON。

| 模型 | 推理控制 | 首选档成功 | TTFT ms | 完整响应 ms | JSON 质量 /100 | 参数接受 | 等级效果 | 相对基线 |
|---|---|---:|---:|---:|---:|---|---|---:|
| `gemini-3.1-flash-lite` | thinkingLevel | 1/1 | 1847 | 3218 | 100 | True | False | 0.54x |
| `gpt-5.4-nano` | reasoning_effort | 1/1 | 1730 | 4220 | 100 | True | True | 0.71x |
| `gpt-5.6-terra` | model_suffix | 1/1 | 2667 | 5571 | 100 | True | False | 0.93x |
| `gpt-5.6-luna` | model_suffix | 1/1 | 3597 | 6058 | 100 | True | True | 1.01x |
| `qwen3.5-35b-a3b` | reasoning_effort | 1/1 | 2143 | 6592 | 100 | True | True | 1.10x |
| `gpt-5.6-sol` | model_suffix | 1/1 | 6575 | 6937 | 100 | True | False | 1.16x |
| `gemini-3.6-flash` | thinkingLevel | 0/1 | 3524 | 3800 | 10 | True | False | 0.64x |
| `gemini-3.5-flash-lite` | thinkingLevel | 0/1 | 5863 | 5972 | 10 | True | False | 1.00x |

## 严格可用模型

- `gemini-3.1-flash-lite`
- `gpt-5.4-nano`
- `gpt-5.6-terra`
- `gpt-5.6-luna`
- `qwen3.5-35b-a3b`
- `gpt-5.6-sol`

## 判定说明

只有最低档与最高档都通过严格 JSON 验证，且最高档耗时至少高 15% 或 reasoning token 明显增加，才标记等级效果可观察。该结论只适用于本附件请求，不代表模型的通用能力保证。

## 各推理等级结果

| 模型 | 控制方式 | 等级 | 成功 | TTFT ms | 完整响应 ms | JSON 质量 | JSON 可解析 | Schema | 7 组覆盖 | 字面量 | Reasoning tokens |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| `gemini-3.1-flash-lite` | thinkingLevel | minimal | 1/1 | 1847 | 3218 | 100 | 1/1 | 1/1 | 1/1 | 1/1 | 0 |
| `gemini-3.1-flash-lite` | thinkingLevel | low | 1/1 | 2145 | 3421 | 100 | 1/1 | 1/1 | 1/1 | 1/1 | 0 |
| `gemini-3.1-flash-lite` | thinkingLevel | medium | 1/1 | 1778 | 3061 | 100 | 1/1 | 1/1 | 1/1 | 1/1 | 0 |
| `gemini-3.1-flash-lite` | thinkingLevel | high | 1/1 | 2082 | 3393 | 100 | 1/1 | 1/1 | 1/1 | 1/1 | 0 |
| `gpt-5.4-nano` | reasoning_effort | minimal | 1/1 | 1730 | 4220 | 100 | 1/1 | 1/1 | 1/1 | 1/1 | 0 |
| `gpt-5.4-nano` | reasoning_effort | high | 1/1 | 6704 | 9339 | 100 | 1/1 | 1/1 | 1/1 | 1/1 | 950 |
| `gpt-5.6-terra` | model_suffix | low | 1/1 | 2667 | 5571 | 100 | 1/1 | 1/1 | 1/1 | 1/1 | 0 |
| `gpt-5.6-terra` | model_suffix | medium | 1/1 | 5799 | 6138 | 100 | 1/1 | 1/1 | 1/1 | 1/1 | 0 |
| `gpt-5.6-terra` | model_suffix | high | 1/1 | 2358 | 5205 | 100 | 1/1 | 1/1 | 1/1 | 1/1 | 0 |
| `gpt-5.6-luna` | model_suffix | low | 1/1 | 3597 | 6058 | 100 | 1/1 | 1/1 | 1/1 | 1/1 | 0 |
| `gpt-5.6-luna` | model_suffix | medium | 1/1 | 5719 | 7730 | 100 | 1/1 | 1/1 | 1/1 | 1/1 | 132 |
| `gpt-5.6-luna` | model_suffix | high | 1/1 | 5463 | 7751 | 100 | 1/1 | 1/1 | 1/1 | 1/1 | 142 |
| `qwen3.5-35b-a3b` | reasoning_effort | minimal | 1/1 | 2143 | 6592 | 100 | 1/1 | 1/1 | 1/1 | 1/1 | - |
| `qwen3.5-35b-a3b` | reasoning_effort | high | 1/1 | 51111 | 55366 | 100 | 1/1 | 1/1 | 1/1 | 1/1 | 5968 |
| `gpt-5.6-sol` | model_suffix | low | 1/1 | 6575 | 6937 | 100 | 1/1 | 1/1 | 1/1 | 1/1 | 0 |
| `gpt-5.6-sol` | model_suffix | medium | 1/1 | 5896 | 6193 | 100 | 1/1 | 1/1 | 1/1 | 1/1 | 0 |
| `gpt-5.6-sol` | model_suffix | high | 1/1 | 5691 | 5990 | 100 | 1/1 | 1/1 | 1/1 | 1/1 | 0 |
| `gemini-3.6-flash` | thinkingLevel | minimal | 0/1 | 3524 | 3800 | 10 | 0/1 | 0/1 | 0/1 | 0/1 | 0 |
| `gemini-3.6-flash` | thinkingLevel | low | 0/1 | 3181 | 3547 | 10 | 0/1 | 0/1 | 0/1 | 0/1 | 0 |
| `gemini-3.6-flash` | thinkingLevel | medium | 0/1 | 3384 | 3690 | 10 | 0/1 | 0/1 | 0/1 | 0/1 | 0 |
| `gemini-3.6-flash` | thinkingLevel | high | 0/1 | 3106 | 3483 | 10 | 0/1 | 0/1 | 0/1 | 0/1 | 0 |
| `gemini-3.5-flash-lite` | thinkingLevel | minimal | 0/1 | 5863 | 5972 | 10 | 0/1 | 0/1 | 0/1 | 0/1 | 1567 |
| `gemini-3.5-flash-lite` | thinkingLevel | low | 0/1 | 5757 | 5951 | 10 | 0/1 | 0/1 | 0/1 | 0/1 | 1574 |
| `gemini-3.5-flash-lite` | thinkingLevel | medium | 0/1 | 5914 | 5916 | 10 | 0/1 | 0/1 | 0/1 | 0/1 | 1868 |
| `gemini-3.5-flash-lite` | thinkingLevel | high | 0/1 | 4938 | 4940 | 10 | 0/1 | 0/1 | 0/1 | 0/1 | 1785 |

## 前 10 页目录可调用模型

模型广场默认每页 30 条，前 10 页共 300 条目录记录，其中 190 条是暴露 OpenAI Chat Completions 接口的文本对话模型。
目录可调用不等于已通过 JSON 翻译验证；只有状态为 `已实测` 的行在本轮实际发起了请求。

| 排位 | 页码 | 模型 | 状态 | 推理控制推测 | 标签 | 市场调用量 |
|---:|---:|---|---|---|---|---:|
| 1 | 1 | `gemini-2.5-flash-lite` | 仅目录可调用 | thinkingLevel | 对话,识图 | 30371825 |
| 2 | 1 | `gpt-5.6-sol` | 已实测 | model_suffix | 对话,工具,识图,思考 | 15031470 |
| 3 | 1 | `gpt-4o-mini` | 仅目录可调用 | unsupported | 对话,识图 | 11872314 |
| 4 | 1 | `gpt-5.4-mini` | 仅目录可调用 | reasoning_effort | 对话,识图,思考,工具 | 11595812 |
| 5 | 1 | `gpt-4.1-nano` | 仅目录可调用 | unsupported | 工具,对话,识图 | 11441145 |
| 6 | 1 | `gemini-3-flash-preview` | 仅目录可调用 | thinkingLevel | 对话,识图,工具 | 11204458 |
| 7 | 1 | `gpt-5.6-luna` | 已实测 | model_suffix | 对话,工具,识图 | 9028616 |
| 8 | 1 | `deepseek-v4-flash-0731` | 仅目录可调用 | reasoning_effort | 对话,工具 | 8341612 |
| 9 | 1 | `gpt-4.1-mini` | 仅目录可调用 | unsupported | 工具,对话,识图 | 7794777 |
| 10 | 1 | `gemini-3.1-flash-lite` | 已实测 | thinkingLevel | 对话,工具,识图 | 6306098 |
| 11 | 1 | `gemini-3.5-flash-lite` | 已实测 | thinkingLevel | 对话,识图,工具 | 5274694 |
| 13 | 1 | `gpt-5.6-terra` | 已实测 | model_suffix | 对话,工具,识图 | 3028549 |
| 14 | 1 | `gemini-3.5-flash` | 仅目录可调用 | thinkingLevel | 对话,工具,识图 | 2936176 |
| 15 | 1 | `deepseek-v4-flash` | 仅目录可调用 | reasoning_effort | 对话,工具 | 2680995 |
| 18 | 1 | `gpt-5.5` | 仅目录可调用 | model_suffix | 对话,识图 | 1576952 |
| 19 | 1 | `gemini-3.1-pro-preview` | 仅目录可调用 | thinkingLevel | 对话,思考,多模态 | 1487044 |
| 20 | 1 | `gemini-2.5-flash` | 仅目录可调用 | thinkingLevel | 对话,识图 | 1471889 |
| 21 | 1 | `gpt-4.1` | 仅目录可调用 | unsupported | 工具,对话,识图 | 1404870 |
| 22 | 1 | `gpt-5.2` | 仅目录可调用 | reasoning_effort | 对话,识图,工具 | 1384429 |
| 24 | 1 | `deepseek-v3.2` | 仅目录可调用 | reasoning_effort | 对话,工具 | 1131470 |
| 28 | 1 | `gpt-5.4-nano` | 已实测 | reasoning_effort | 对话,识图,思考,工具 | 954438 |
| 29 | 1 | `gemini-3.6-flash` | 已实测 | thinkingLevel | 对话,识图,工具 | 934866 |
| 30 | 1 | `gpt-4o` | 仅目录可调用 | unsupported | 对话,识图 | 884220 |
| 34 | 2 | `gemini-2.5-pro` | 仅目录可调用 | thinkingLevel | 对话,识图,思考 | 561754 |
| 35 | 2 | `claude-opus-5` | 仅目录可调用 | reasoning_effort | 文本,识图,工具 | 537026 |
| 37 | 2 | `deepseek-v4-pro` | 仅目录可调用 | reasoning_effort | 对话,思考,工具 | 482358 |
| 38 | 2 | `claude-opus-4-8` | 仅目录可调用 | reasoning_effort | 对话,识图,工具 | 481679 |
| 39 | 2 | `gpt-4o-mini-2024-07-18` | 仅目录可调用 | unsupported | 对话,识图 | 341614 |
| 40 | 2 | `claude-haiku-4-5-20251001` | 仅目录可调用 | reasoning_effort | 对话,识图,思考,工具 | 327214 |
| 41 | 2 | `claude-sonnet-5` | 仅目录可调用 | reasoning_effort | 对话,识图,工具 | 323170 |
| 42 | 2 | `gpt-5.1` | 仅目录可调用 | reasoning_effort | 对话,识图,工具 | 299379 |
| 43 | 2 | `qwen3-32b` | 仅目录可调用 | reasoning_effort | 对话,识图 | 274944 |
| 44 | 2 | `grok-4.3` | 仅目录可调用 | unsupported | 对话,工具,思考 | 257729 |
| 45 | 2 | `gpt-5-mini-2025-08-07` | 仅目录可调用 | reasoning_effort | 对话,识图,思考 | 223032 |
| 46 | 2 | `claude-sonnet-4-6` | 仅目录可调用 | reasoning_effort | 对话,识图,工具 | 218894 |
| 47 | 2 | `gpt-5` | 仅目录可调用 | reasoning_effort | 对话,思考,工具,识图 | 195805 |
| 49 | 2 | `gpt-5.4-mini-2026-03-17` | 仅目录可调用 | reasoning_effort | 对话,识图,思考,工具 | 133803 |
| 52 | 2 | `glm-5.2` | 仅目录可调用 | unsupported | 对话,工具 | 122810 |
| 53 | 2 | `gpt-chat-latest` | 仅目录可调用 | unsupported | 对话,工具 | 122269 |
| 54 | 2 | `kimi-k2.7-code` | 仅目录可调用 | unsupported | 对话,工具 | 119721 |
| 55 | 2 | `claude-fable-5` | 仅目录可调用 | reasoning_effort | 对话,识图,工具 | 111341 |
| 56 | 2 | `claude-opus-4-6` | 仅目录可调用 | reasoning_effort | 对话,识图,工具 | 105631 |
| 57 | 2 | `gpt-5-nano-2025-08-07` | 仅目录可调用 | reasoning_effort | 对话,识图,思考 | 102864 |
| 59 | 2 | `gemini-3.1-flash-lite-preview` | 仅目录可调用 | thinkingLevel | 对话,识图 | 88730 |
| 62 | 3 | `gpt-5.1-2025-11-13` | 仅目录可调用 | reasoning_effort | 对话,识图 | 76420 |
| 63 | 3 | `claude-opus-4-7` | 仅目录可调用 | reasoning_effort | 对话,识图,工具 | 74187 |
| 64 | 3 | `gemini-3-pro-preview` | 仅目录可调用 | thinkingLevel | 对话,思考,多模态 | 67531 |
| 65 | 3 | `claude-sonnet-4-5-20250929` | 仅目录可调用 | reasoning_effort | 对话,识图,工具 | 64832 |
| 66 | 3 | `qwen3.6-plus` | 仅目录可调用 | reasoning_effort | 对话,工具,识图 | 64504 |
| 68 | 3 | `qwen3.7-plus` | 仅目录可调用 | reasoning_effort | 对话,工具 | 52556 |
| 71 | 3 | `qwen3.6-27b` | 仅目录可调用 | reasoning_effort | 对话,识图 | 39128 |
| 72 | 3 | `grok-4-1-fast-non-reasoning` | 仅目录可调用 | none | 对话 | 38644 |
| 73 | 3 | `kimi-k2.6` | 仅目录可调用 | unsupported | 对话 | 37788 |
| 74 | 3 | `grok-4` | 仅目录可调用 | unsupported | 对话,识图 | 34852 |
| 75 | 3 | `gpt-4.1-2025-04-14` | 仅目录可调用 | unsupported | 对话,识图 | 34335 |
| 76 | 3 | `qwen-plus` | 仅目录可调用 | reasoning_effort | 对话 | 31102 |
| 78 | 3 | `qwen3-coder-flash` | 仅目录可调用 | reasoning_effort | 对话,工具 | 30632 |
| 79 | 3 | `gpt-5-2025-08-07` | 仅目录可调用 | reasoning_effort | 对话,识图,思考 | 27778 |
| 80 | 3 | `glm-5.1` | 仅目录可调用 | unsupported | 对话,思考 | 25816 |
| 84 | 3 | `grok-4-1-fast-reasoning` | 仅目录可调用 | reasoning_effort | 对话 | 20061 |
| 85 | 3 | `kimi-k2.5` | 仅目录可调用 | unsupported | 对话,识图,工具 | 19828 |
| 86 | 3 | `gpt-4.1-mini-2025-04-14` | 仅目录可调用 | unsupported | 对话,识图 | 19618 |
| 88 | 3 | `qwen3-vl-235b-a22b-thinking` | 仅目录可调用 | reasoning_effort | 对话,识图,思考 | 19081 |
| 89 | 3 | `gpt-4.1-nano-2025-04-14` | 仅目录可调用 | unsupported | 对话,识图 | 17034 |
| 90 | 3 | `gpt-5.4-nano-2026-03-17` | 仅目录可调用 | reasoning_effort | 对话,识图,思考,工具 | 16319 |
| 91 | 4 | `doubao-seed-2-0-mini-260428` | 仅目录可调用 | unsupported | 对话,工具 | 13294 |
| 93 | 4 | `deepseek-v3` | 仅目录可调用 | reasoning_effort | 对话 | 13005 |
| 94 | 4 | `qwen3.5-27b` | 仅目录可调用 | reasoning_effort | 对话,识图 | 12220 |
| 96 | 4 | `doubao-seed-2-0-lite-260428` | 仅目录可调用 | unsupported | 对话,工具 | 11596 |
| 97 | 4 | `qwen3.7-max` | 仅目录可调用 | reasoning_effort | 对话,工具 | 11365 |
| 99 | 4 | `gpt-4o-2024-11-20` | 仅目录可调用 | unsupported | 对话,识图 | 10964 |
| 101 | 4 | `grok-build-0.1` | 仅目录可调用 | unsupported | 对话,识图,工具 | 9787 |
| 102 | 4 | `gpt-5.3-codex` | 仅目录可调用 | reasoning_effort | 对话,识图 | 9787 |
| 103 | 4 | `qwen3.6-max-preview` | 仅目录可调用 | reasoning_effort | 对话,工具 | 9653 |
| 107 | 4 | `qwen3-235b-a22b` | 仅目录可调用 | reasoning_effort | 对话,识图 | 8017 |
| 108 | 4 | `qwen3-8b` | 仅目录可调用 | reasoning_effort | 对话,识图 | 7937 |
| 109 | 4 | `gpt-4-turbo-2024-04-09` | 仅目录可调用 | unsupported | 对话 | 7631 |
| 110 | 4 | `qwen3.6-35b-a3b` | 仅目录可调用 | reasoning_effort | 识图,对话 | 6975 |
| 111 | 4 | `deepseek-r1-0528` | 仅目录可调用 | reasoning_effort | 对话,思考 | 6842 |
| 112 | 4 | `claude-opus-4-5-20251101` | 仅目录可调用 | reasoning_effort | 对话,识图,工具 | 6112 |
| 114 | 4 | `qwen3-vl-235b-a22b-instruct` | 仅目录可调用 | reasoning_effort | 对话,识图 | 5535 |
| 116 | 4 | `claude-3-haiku-20240307` | 仅目录可调用 | reasoning_effort | 对话,识图 | 5093 |
| 117 | 4 | `qwen3.5-397b-a17b` | 仅目录可调用 | reasoning_effort | 对话,思考 | 4802 |
| 118 | 4 | `gpt-4o-2024-05-13` | 仅目录可调用 | unsupported | 对话,识图 | 4755 |
| 120 | 4 | `llama-3.1-8b` | 仅目录可调用 | unsupported | 对话 | 4393 |
| 121 | 5 | `qwen3.5-plus` | 仅目录可调用 | reasoning_effort | 对话,思考 | 4377 |
| 122 | 5 | `o3` | 仅目录可调用 | unsupported | 对话,思考 | 4233 |
| 123 | 5 | `deepseek-r1` | 仅目录可调用 | reasoning_effort | 对话,思考 | 4101 |
| 126 | 5 | `qwen3.5-122b-a10b` | 仅目录可调用 | reasoning_effort | 对话,识图,思考 | 3792 |
| 128 | 5 | `glm-5` | 仅目录可调用 | unsupported | 对话,工具 | 3519 |
| 129 | 5 | `gpt-5.3-chat-latest` | 仅目录可调用 | reasoning_effort | 对话,识图,工具 | 3483 |
| 130 | 5 | `qwen3-235b-a22b-instruct-2507` | 仅目录可调用 | reasoning_effort | 对话 | 3313 |
| 131 | 5 | `grok-3-mini` | 仅目录可调用 | unsupported | 对话 | 2874 |
| 133 | 5 | `deepseek-v3.1` | 仅目录可调用 | reasoning_effort | 对话,思考 | 2582 |
| 135 | 5 | `MiniMax-M2.7` | 仅目录可调用 | unsupported | 对话,工具 | 2267 |
| 136 | 5 | `qwen3-30b-a3b-instruct-2507` | 仅目录可调用 | reasoning_effort | 对话,识图 | 2216 |
| 137 | 5 | `grok-4-20-non-reasoning` | 仅目录可调用 | unsupported | 对话,工具 | 2151 |
| 140 | 5 | `MiniMax-M3` | 仅目录可调用 | unsupported | 对话,工具 | 1913 |
| 142 | 5 | `qwen3-vl-8b-instruct` | 仅目录可调用 | reasoning_effort | 对话,识图 | 1847 |
| 143 | 5 | `llama-3.2-3b-instruct` | 仅目录可调用 | unsupported | 对话 | 1752 |
| 144 | 5 | `doubao-seed-2-0-pro-260215` | 仅目录可调用 | unsupported | 对话,工具 | 1635 |
| 145 | 5 | `glm-4.6` | 仅目录可调用 | unsupported | 对话 | 1434 |
| 147 | 5 | `llama-2-7b` | 仅目录可调用 | unsupported | 对话 | 1425 |
| 148 | 5 | `qwen3-vl-32b-instruct` | 仅目录可调用 | reasoning_effort | 对话,工具 | 1282 |
| 149 | 5 | `deepseek-v3.2-exp` | 仅目录可调用 | reasoning_effort | 对话 | 1268 |
| 151 | 6 | `glm-4-flash` | 仅目录可调用 | unsupported | 对话 | 1253 |
| 155 | 6 | `qwen3-vl-32b-thinking` | 仅目录可调用 | reasoning_effort | 对话,思考,工具 | 1139 |
| 157 | 6 | `llama-3.3-70b-instruct` | 仅目录可调用 | unsupported | 对话 | 1044 |
| 158 | 6 | `qwen3-coder-480b-a35b-instruct` | 仅目录可调用 | reasoning_effort | 对话,思考 | 998 |
| 160 | 6 | `doubao-seed-1-8-251228` | 仅目录可调用 | unsupported | 对话,识图,工具 | 977 |
| 161 | 6 | `o1-2024-12-17` | 仅目录可调用 | unsupported | 对话 | 959 |
| 162 | 6 | `o4-mini` | 仅目录可调用 | unsupported | 对话,识图 | 911 |
| 163 | 6 | `gpt-3.5-turbo-1106` | 仅目录可调用 | unsupported | 对话,识图 | 899 |
| 164 | 6 | `qwen3.5-35b-a3b` | 已实测 | reasoning_effort | 对话,识图,工具 | 835 |
| 173 | 6 | `o3-mini` | 仅目录可调用 | unsupported | 对话 | 484 |
| 174 | 6 | `qwen3-vl-flash` | 仅目录可调用 | reasoning_effort | 对话,识图 | 475 |
| 175 | 6 | `qwen3-max` | 仅目录可调用 | reasoning_effort | 对话 | 472 |
| 176 | 6 | `qwen3.5-plus-2026-02-15` | 仅目录可调用 | reasoning_effort | 对话,思考 | 463 |
| 177 | 6 | `qwen3-vl-8b-thinking` | 仅目录可调用 | reasoning_effort | 对话,识图,思考 | 457 |
| 178 | 6 | `gpt-5-search-api` | 仅目录可调用 | reasoning_effort | 对话,联网 | 433 |
| 179 | 6 | `gemini-flash-lite-latest` | 仅目录可调用 | thinkingLevel | 对话,识图 | 418 |
| 180 | 6 | `grok-4-20-reasoning` | 仅目录可调用 | unsupported | 对话,工具 | 403 |
| 181 | 7 | `glm-4.7` | 仅目录可调用 | unsupported | 对话,思考 | 403 |
| 182 | 7 | `qwen-plus-2025-12-01` | 仅目录可调用 | reasoning_effort | 对话 | 387 |
| 184 | 7 | `o3-mini-2025-01-31` | 仅目录可调用 | unsupported | 对话 | 370 |
| 187 | 7 | `glm-4` | 仅目录可调用 | unsupported | 对话 | 325 |
| 188 | 7 | `qwen3-30b-a3b-thinking-2507` | 仅目录可调用 | reasoning_effort | 对话,识图,思考 | 276 |
| 190 | 7 | `claude-opus-4-1-20250805` | 仅目录可调用 | reasoning_effort | 对话,识图,工具 | 261 |
| 191 | 7 | `mimo-v2.5` | 仅目录可调用 | unsupported | 对话,工具 | 258 |
| 192 | 7 | `doubao-seed-2-0-code-preview-260215` | 仅目录可调用 | unsupported | 对话,思考,识图 | 252 |
| 193 | 7 | `qwen-turbo` | 仅目录可调用 | reasoning_effort | 对话 | 249 |
| 195 | 7 | `kimi-k2-0711-preview` | 仅目录可调用 | unsupported | 对话 | 236 |
| 196 | 7 | `qwen-plus-character` | 仅目录可调用 | reasoning_effort | 对话 | 224 |
| 197 | 7 | `grok-3` | 仅目录可调用 | unsupported | 对话 | 193 |
| 198 | 7 | `doubao-seed-1-6-flash-250828` | 仅目录可调用 | unsupported | 对话,思考,多模态 | 191 |
| 199 | 7 | `doubao-seed-2-0-lite-260215` | 仅目录可调用 | unsupported | 对话,识图 | 190 |
| 200 | 7 | `qwen-vl-max` | 仅目录可调用 | reasoning_effort | 对话,识图 | 186 |
| 202 | 7 | `o1` | 仅目录可调用 | unsupported | 对话 | 149 |
| 203 | 7 | `mimo-v2.5-pro` | 仅目录可调用 | unsupported | 对话,工具 | 148 |
| 205 | 7 | `gpt-5.2-chat-latest` | 仅目录可调用 | reasoning_effort | 对话,识图 | 146 |
| 208 | 7 | `qwen-flash` | 仅目录可调用 | reasoning_effort | 对话 | 132 |
| 209 | 7 | `qwen3-max-2026-01-23` | 仅目录可调用 | reasoning_effort | 对话,思考 | 130 |
| 212 | 8 | `qwen3.6-plus-2026-04-02` | 仅目录可调用 | reasoning_effort | 对话,工具,识图 | 97 |
| 213 | 8 | `gpt-oss-120b` | 仅目录可调用 | unsupported | 对话 | 94 |
| 215 | 8 | `glm-4.5-air` | 仅目录可调用 | unsupported | 对话,思考 | 76 |
| 217 | 8 | `qwen3-coder-plus` | 仅目录可调用 | reasoning_effort | 对话,思考 | 73 |
| 219 | 8 | `qwen3-vl-plus` | 仅目录可调用 | reasoning_effort | 对话,识图 | 72 |
| 221 | 8 | `gpt-4o-mini-search-preview` | 仅目录可调用 | unsupported | 对话,联网 | 66 |
| 223 | 8 | `gpt-5.2-codex` | 仅目录可调用 | reasoning_effort | 对话 | 62 |
| 225 | 8 | `grok-4-fast-reasoning` | 仅目录可调用 | reasoning_effort | 对话,思考 | 58 |
| 227 | 8 | `llama-3.1-70b` | 仅目录可调用 | unsupported | 对话 | 52 |
| 230 | 8 | `llama-3.3-70b` | 仅目录可调用 | unsupported | 对话 | 38 |
| 231 | 8 | `grok-4-fast-non-reasoning` | 仅目录可调用 | unsupported | 工具,多模态,对话 | 34 |
| 232 | 8 | `qwen-max` | 仅目录可调用 | reasoning_effort | 对话 | 33 |
| 234 | 8 | `gemini-flash-latest` | 仅目录可调用 | thinkingLevel | 对话,识图 | 30 |
| 237 | 8 | `qwen-plus-latest` | 仅目录可调用 | reasoning_effort | 对话 | 26 |
| 238 | 8 | `qwen3-30b-a3b-think` | 仅目录可调用 | reasoning_effort | 对话,识图,思考 | 25 |
| 240 | 8 | `deepseek-v4-pro-0813` | 仅目录可调用 | reasoning_effort | 对话,工具,思考 | 22 |
| 242 | 9 | `qwen3-vl-30b-a3b-instruct` | 仅目录可调用 | reasoning_effort | 对话,识图 | 20 |
| 244 | 9 | `deepseek-v3-1` | 仅目录可调用 | reasoning_effort | 对话 | 20 |
| 251 | 9 | `qwen3-max-preview` | 仅目录可调用 | reasoning_effort | 对话 | 18 |
| 254 | 9 | `doubao-seed-2-0-mini-260215` | 仅目录可调用 | unsupported | 对话,识图 | 15 |
| 255 | 9 | `gpt-5-search-api-2025-10-14` | 仅目录可调用 | reasoning_effort | 对话,联网 | 15 |
| 256 | 9 | `llama-3.2-90b-vision-instruct` | 仅目录可调用 | unsupported | 对话 | 15 |
| 257 | 9 | `deepseek-v3-1-250821` | 仅目录可调用 | reasoning_effort | 对话,思考 | 14 |
| 258 | 9 | `o3-2025-04-16` | 仅目录可调用 | unsupported | 对话,思考 | 14 |
| 260 | 9 | `gpt-4-turbo` | 仅目录可调用 | unsupported | 对话 | 13 |
| 262 | 9 | `o4-mini-2025-04-16` | 仅目录可调用 | unsupported | 对话,识图 | 12 |
| 263 | 9 | `qwq-32b` | 仅目录可调用 | unsupported | 对话,识图,思考 | 11 |
| 265 | 9 | `glm-4-long` | 仅目录可调用 | unsupported | 对话 | 10 |
| 268 | 9 | `qwq-72b-preview` | 仅目录可调用 | unsupported | 对话 | 9 |
| 270 | 9 | `glm-4-airx` | 仅目录可调用 | unsupported | 对话 | 8 |
| 274 | 10 | `MiniMax-M2.5` | 仅目录可调用 | unsupported | 对话 | 6 |
| 276 | 10 | `llama-3-70b` | 仅目录可调用 | unsupported | 对话 | 6 |
| 279 | 10 | `SparkDesk-v1.1` | 仅目录可调用 | unsupported | 对话 | 5 |
| 280 | 10 | `SparkDesk-v3.5` | 仅目录可调用 | unsupported | 对话 | 5 |
| 281 | 10 | `gpt-4` | 仅目录可调用 | unsupported | 对话 | 5 |
| 282 | 10 | `llama-2-70b` | 仅目录可调用 | unsupported | 对话 | 5 |
| 283 | 10 | `qwen3-vl-30b-a3b-thinking` | 仅目录可调用 | reasoning_effort | 对话,识图,思考 | 4 |
| 284 | 10 | `qwen3-next-80b-a3b-instruct` | 仅目录可调用 | reasoning_effort | 对话 | 4 |
| 285 | 10 | `qwen3-coder-30b-a3b-instruct` | 仅目录可调用 | reasoning_effort | 对话,思考 | 4 |
| 286 | 10 | `SparkDesk-v2.1` | 仅目录可调用 | unsupported | 对话 | 4 |
| 287 | 10 | `SparkDesk-v3.1` | 仅目录可调用 | unsupported | 对话 | 4 |
| 288 | 10 | `deepseek-v3-0324` | 仅目录可调用 | reasoning_effort | 对话 | 4 |
| 290 | 10 | `llama-3-8b` | 仅目录可调用 | unsupported | 对话 | 4 |
| 291 | 10 | `llama-3.1-405b` | 仅目录可调用 | unsupported | 对话 | 4 |
| 292 | 10 | `llama-3.2-1b-instruct` | 仅目录可调用 | unsupported | 对话 | 4 |
| 298 | 10 | `gpt-3.5-turbo-0125` | 仅目录可调用 | unsupported | 对话,识图 | 3 |
| 299 | 10 | `gpt-3.5-turbo-16k` | 仅目录可调用 | unsupported | 对话,识图 | 3 |
| 300 | 10 | `gpt-4-0613` | 仅目录可调用 | unsupported | 对话 | 3 |

---

## V4 数据库离散 Case 冷启动覆盖（2026-08-13）

本节是对上方单附件结果的扩展验证。完整工件位于 `docs/validation/openlux-v4-cold-cases-2026-08-13/`：`REPORT.md` 为详细报告，`cases.json` 为抽样数据，`results.json`/`runs.csv` 为 95 次请求的原始结果，`QUALITY_ASSESSMENT.json` 为语义质量复核。

### Case 方法

1. 从本地 `demo-server/demo-server.sqlite3` 只读抽取 `status='SUCCEEDED'`、`schemaVersion=4`、历史响应 `failedGroupCount=0` 的合法 JSON 数据。
2. 使用实际 `model_request_json.translateGroups`；要求历史译文数量一致、非空且满足必保留字面量。排除自然语言字符少于 60 或最长文本组少于 80 字符的标签型记录，最终有 127 条记录符合抽样条件。
3. 按源文字符数分为 `80-299 / 300-599 / 600-899 / 900-1149 / >=1150` 五档；固定种子 `20260813` 在每档随机抽一条，并排除与已选文本相似度达到 0.72 的候选。
4. 语义质量人工逐组对照源文评分：准确性 40、完整性 25、技术术语 20、流畅度与 OCR 鲁棒性 15。综合排名使用成功输出语义质量 55%、严格交付可靠性 30%、冷启动速度 15%；失败由可靠性项单独惩罚，避免重复扣分。

| Case | 字符数 | 分组 | audit | 主要覆盖 |
|---|---:|---:|---|---|
| XS | 133 | 3 | `05d040bd-baa5-4e9e-a794-8ac950edb19b` | API 文档、路径保持 |
| S | 436 | 4 | `87f7b82f-3ba6-48c9-9cdb-d3c3c04f889f` | OCR 噪声、Rust 教程 |
| M | 888 | 6 | `98c4b39d-6db0-4961-8d31-9456de3d6c56` | 技术术语、必保留字面量 |
| L | 990 | 5 | `cf6036dc-c36b-40dc-af3d-62d0c79580b6` | Cargo、截断文本 |
| XL | 1337 | 5 | `061e7404-8c41-4e0a-819b-926ed432c7fb` | 长篇技术说明、OCR 缺字 |

### 冷启动定义

每次请求均启动新 `curl` 进程，强制 HTTP/1.1 与 `Connection: close`，发送 `Cache-Control: no-cache, no-store, max-age=0`、`Pragma: no-cache`，并在 system prompt 中加入唯一 nonce。所有请求单线程串行，间隔 2 秒，429 时计划冷却 10 秒；本轮无 429。严格成功要求 HTTP 200 且 JSON 顶层、schema、全部 groupId 和必保留字面量四项均通过。

### Top 5 结果

| 排名 | 模型与首选档 | 严格成功 | TTFT 中位数 | 完整响应中位数 | JSON 中位分 | 语义质量 | 综合分 |
|---:|---|---:|---:|---:|---:|---:|---:|
| 1 | `gemini-3.1-flash-lite` / minimal | 5/5 | 2089 ms | 3225 ms | 100 | 94.6 | 97.0 |
| 2 | `gpt-5.6-sol` / low | 5/5 | 3736 ms | 5839 ms | 100 | 96.4 | 91.3 |
| 3 | `gpt-5.6-luna` / low | 5/5 | 3829 ms | 5205 ms | 100 | 94.2 | 91.1 |
| 4 | `gpt-5.4-nano` / minimal | 5/5 | 2599 ms | 5335 ms | 100 | 91.6 | 89.5 |
| 5 | `gpt-5.6-terra` / low | 4/5 | 3434 ms* | 5671 ms* | 100 | 95.8* | 85.2 |

`*` Terra 的 TTFT/响应中位数仅统计 4 次成功请求；另一次约 50.95 秒后收到空响应。成功输出的语义质量均值为 95.8，但可靠性使其降至第五。

### 推理强度覆盖

| 模型 | 控制 | 等级 | 严格成功 | 完整响应中位数 | JSON 中位分 | Reasoning tokens 中位数 |
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

### 结论

- 默认路由：`gemini-3.1-flash-lite` + `thinkingLevel=minimal`，速度和 JSON 稳定性最佳。
- 质量优先：`gpt-5.6-sol-low`，技术术语与整体流畅度最高。
- 稳定回退：`gpt-5.6-luna-low`；`gpt-5.4-nano-minimal` 可作为快速补充，但技术文档建议配术语表。
- `thinkingLevel` 四档在 Gemini 上均被接受且 20/20 严格成功，但耗时不单调、reasoning tokens 均为 0，当前证据不足以证明档位实际生效。
- Qwen 推理档位的 token 与耗时差异明显，但四档仅 5/20 严格成功。失败主要是将 `translations` 数组改成对象或直接返回顶层数组，另有一次 120 秒超时；`low` 仅 1/5 成功且成功样本耗时 41.38 秒，不适合严格 JSON 短文档路径。

---

## Google 原生链接经代理复测（2026-08-13）

需要明确区分两条链路：前一节虽然使用了 `http://127.0.0.1:7897` 代理，但请求目标是 OpenLux 的 `/v1/chat/completions`；本节直接请求 Google 原生地址：

`https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash-lite:generateContent`

鉴权使用 `x-goog-api-key`，代理仍为 `http://127.0.0.1:7897`。复用相同 5 个离散 V4 case 和同一冷启动方法，新连接、无缓存头、唯一 nonce、串行 2 秒间隔，共 20 次请求且无 429。完整报告位于 `docs/validation/google-native-gemini-v4-cold-cases-2026-08-13/REPORT.md`。

| thinkingLevel | 严格成功 | 完整响应中位数 | JSON 中位分 | thought tokens 证据 |
|---|---:|---:|---:|---|
| `minimal` | 5/5 | 2528 ms | 100 | 未暴露 |
| `low` | 5/5 | 2398 ms | 100 | 未暴露 |
| `medium` | 5/5 | 2508 ms | 100 | 未暴露 |
| `high` | 5/5 | 2439 ms | 100 | XL case 暴露 2049 |

Google 原生链路合计 20/20 严格 JSON 成功。XL case 的 `high` 产生 2049 thought tokens，耗时 10144 ms，而该 case 的 `minimal` 为 3170 ms，证明参数在复杂内容上可以实际生效；其他多数请求没有 thought token 且四档耗时接近，说明额外推理可能按内容触发。

人工源文对照中，`minimal` 五档约 95/100，主要问题是把 Rust 语境的 `ergonomics` 译成“人机工程学”。`high` 在 XL OCR 损坏文本中更保守地处理缺失的附录字母，并正确保留 `nightly`，但延迟约为 `minimal` 的 3.2 倍。

因此，如果采用 Google 原生链接经代理访问，建议默认 `gemini-3.5-flash-lite + thinkingLevel=minimal`；OCR 损坏较重、质量优先且能接受尾延迟时再按需使用 `high`。这项结论只适用于 Google 原生 `generateContent` 链路，不能与 OpenLux 兼容接口结果混用。

---

## 最终综合选型与生产参数

本节综合 Google 原生链路 20 次和 OpenLux 链路 95 次结果，覆盖前文各单轮“默认模型”结论，作为当前短文档 OCR 英译中、严格 JSON 场景的最终建议。两条链路的延迟分别评估，不把不同 Provider 的样本直接合并计算。

| 优先级 | Provider / 模型 | 推理参数 | 超时 | 严格成功 | 响应中位数 | 语义质量 | 定位 |
|---:|---|---|---:|---:|---:|---:|---|
| 主模型 | Google Native / `gemini-3.5-flash-lite` | `thinkingLevel=MINIMAL` | 6 秒 | 5/5 | 2528 ms | 约 95 | 默认翻译路径 |
| 备选 1 | OpenLux / `gpt-5.6-sol-low` | `-low` 模型后缀 | 12 秒 | 5/5 | 5839 ms | 96.4 | 质量优先、主链路失败 |
| 备选 2 | OpenLux / `gpt-5.6-luna-low` | `-low` 模型后缀 | 15 秒 | 5/5 | 5205 ms | 94.2 | 独立稳定回退 |
| 应急备选 | OpenLux / `gpt-5.4-nano` | `reasoning_effort=minimal` | 12 秒 | 5/5 | 5335 ms | 91.6 | 容量或成本应急 |
| 条件升级 | Google Native / `gemini-3.5-flash-lite` | `thinkingLevel=HIGH` | 15 秒 | 5/5 | 2439 ms* | 约 96 | OCR 严重损坏、允许高尾延迟 |

`*` HIGH 的总体中位数被四条未触发明显思考的请求拉低；XL case 实际产生 2049 thought tokens，耗时 10144 ms，因此生产容量应按 10-15 秒尾延迟规划。

主模型固定 `temperature=0`，使用 `responseMimeType=application/json` 和 V4 严格 `responseJsonSchema`，`maxOutputTokens` 按源文长度和分组数动态计算。Google Base URL 为 `https://generativelanguage.googleapis.com/v1beta`，经 `http://127.0.0.1:7897` 代理访问。

自动回退规则：主模型出现网络错误、6 秒超时、429/5xx、空响应、JSON/schema/groupId/字面量任一校验失败时，立即切换 Sol-low，不在原链路连续重试；Sol 12 秒失败后切换 Luna-low；Nano 只在前两级均不可用时启用。HTTP 200 但 JSON 不符合 V4 契约仍属于失败，禁止宽松解析后交付。

对于不少于约 1200 字符、同时具有明显 OCR 缺字或粘连、且允许 10-15 秒延迟的请求，可以直接使用 Google HIGH。仅仅是技术术语密集时，优先使用 Sol-low，而不是无条件增加 thinkingLevel。

不进入自动链路：`qwen3.5-35b-a3b` 四档仅 5/20 严格成功；`gpt-5.6-terra-low` 有一次约 50.95 秒后的空响应；OpenLux `gemini-3.1-flash-lite-minimal` 虽然快速且 5/5 成功，但与主模型同属 Gemini 家族，故障隔离价值有限，只保留为人工同族备用。

最终组合：`Google gemini-3.5-flash-lite/MINIMAL -> OpenLux gpt-5.6-sol-low -> OpenLux gpt-5.6-luna-low -> OpenLux gpt-5.4-nano/minimal`。它在当前证据下兼顾主路径速度、严格 JSON、翻译质量和跨模型故障隔离。

---

## 2026-08-14 两模型覆盖与十连复测

使用相同 5 个离散 V4 case，对 Google 原生 `gemini-3.5-flash-lite` 和 OpenLux `gpt-5.3-codex-spark` 复测 `omitted/minimal/low/medium/high`。另用 888 字符 case 对每个配置连续串行请求 10 次，分别统计纯请求时间与包含 1 秒节流的墙钟时间。完整报告位于 `docs/validation/two-model-rerun-2026-08-14/REPORT.md`。

Google 覆盖模式除 `high` 一次空响应外为 24/25 严格成功。不传推理参数和 `minimal` 在连续模式均为 10/10，十次纯请求总时间分别为 29.98 秒和 29.75 秒，效果基本相同；生产仍显式使用 `MINIMAL`，避免服务端默认策略变化。

| Google 配置 | 连续严格 JSON | 有效翻译 | 十次纯请求总时间 | 含节流墙钟时间 | 主要风险 |
|---|---:|---:|---:|---:|---|
| omitted | 10/10 | 10/10 | 29.98 秒 | 39.26 秒 | 服务端默认值不透明 |
| minimal | 10/10 | 10/10 | 29.75 秒 | 39.04 秒 | 无明显问题 |
| low | 10/10 | 10/10 | 32.86 秒 | 42.17 秒 | 更慢，无稳定质量增益 |
| medium | 10/10 | 10/10 | 91.73 秒 | 101.03 秒 | 一次 64.25 秒长尾 |
| high | 7/10 | 6/10 | 29.31 秒 | 38.58 秒 | 三次字面量损坏；一次英文原样返回 |

`high` 的英文原样返回仍满足 JSON schema、groupId 和 `targetLanguage=zh`，说明生产验证必须增加目标语言字符或源/译文相似度检查，不能只验证 JSON 格式。

Spark 在覆盖阶段以及多分钟冷却后的 `/chat/completions`、`/responses` 双端点探测中持续返回 HTTP 429：`当前分组上游负载已饱和，请稍后再试`。为遵守限频要求，没有继续执行无意义的十连 429。当前无法评估其真实速度、翻译质量和 JSON 遵循能力，应标记为不可用并排除出生产候选链。

最终生产结论不变：`Google gemini-3.5-flash-lite/MINIMAL -> gpt-5.6-sol-low -> gpt-5.6-luna-low -> gpt-5.4-nano/minimal`。`gpt-5.3-codex-spark` 只有在单次探针连续三次 HTTP 200 后才值得恢复完整复测。

### 截图补测：OpenLux GPT-4.1

截图中的 GPT 模型识别为 `gpt-4.1`。本地没有 Azure endpoint/API key，因此只执行 OpenLux 链路。`reasoning_effort=minimal/low/medium/high` 四档全部被明确拒绝，错误为 `Unrecognized request argument supplied: reasoning_effort`；生产参数必须完全省略该字段。

GPT-4.1 omitted 的离散覆盖为 4/5，一次 TLS 连接超时，并出现 69.05 秒长尾。888 字符 case 连续十次全部 HTTP 200，严格 JSON/groupId 为 9/10，纯请求总时间 93.22 秒、墙钟 102.30 秒、中位 8.59 秒。唯一协议失败是一个 groupId 少写字符；十次译文均真正转换为中文。

人工质量约 95-96/100，`ergonomics`、Cargo、生命周期、trait 和 nightly 等技术术语处理较好；但十连总时间约为 Google minimal 的 3.13 倍。结论是“质量高但慢，协议偶发不稳，不支持推理强度”，只适合人工质量复核或末级候选，不改变当前自动模型链。完整报告：`docs/validation/gpt-4.1-openlux-rerun-2026-08-14/REPORT.md`。
