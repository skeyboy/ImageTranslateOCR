# Ollama / Qwen 请求审计与 Admin 回显验证

## 目标

记录服务端实际发送给 Ollama OpenAI 兼容接口的 JSON 请求体，并在请求详情页提供不干扰现有版面还原的查看入口。

## 实现结果

- `request_payloads` 新增可空字段 `model_request_json`，旧数据库启动时自动补列，旧记录不受影响。
- Qwen 请求构造与审计序列化共用同一个 `ChatRequest`，避免“记录内容”和“实际发送内容”产生漂移。
- 保存模型名、`messages`、温度、随机种子、`reasoning_effort`、响应 JSON Schema，以及服务端筛选后的可翻译语义组。
- 不保存 HTTP `Authorization` 头或 API Key。
- 成功、失败、取消三种终态都保留送模请求；没有可翻译组时不生成虚假的模型请求记录。
- Admin 详情页在原请求/响应区域下方增加默认折叠的“发送给 Ollama / Qwen 的请求”，旧记录显示未采集原因。

## 真实请求验证

- 协议：v3
- 模型：`qwen3.5:9b`
- 请求 ID：`admin-model-audit-20260807-1`
- 审计 ID：`69a1976b-6b60-4d4c-9f2c-960f53c4f417`
- 服务状态：`SUCCEEDED`
- 模型耗时：23402ms
- 模型请求 JSON：6227 bytes
- 服务端计划：6 组
- 实际送模：4 组，ID 为 `group-caption`、`group-story`、`group-pla`、`group-order`
- 保留未送模：2 组
- 消息：2 条，角色为 `system`、`user`
- 响应格式：`json_schema`

查看地址：<http://192.168.0.63:8090/admin/requests/69a1976b-6b60-4d4c-9f2c-960f53c4f417>

记录 `88bbd722-6ffc-4e13-98ee-6a1fd87c1bf6` 创建于此功能启用前，只能显示“历史记录未采集”，无法事后还原当时已经发送并失败的完整请求体。

## 版面验证

| 场景 | 结果 |
| --- | --- |
| 1440 x 1000，折叠 | 原页面还原区和请求/响应区继续双列；模型请求默认关闭；无横向溢出 |
| 1440 x 1000，展开 | JSON 可见，包含模型与 `translateGroups`；无横向溢出 |
| 390 x 844，折叠 | 原区域按既有规则变为单列；折叠摘要换行；无横向溢出 |
| 浏览器控制台 | 无 error/warn |

对应截图：

- `desktop-collapsed.png`
- `desktop-expanded.png`
- `mobile-collapsed.png`

## 自动化验证

- `cargo test`：14 个单元测试、7 个 API 测试通过。
- 旧表迁移测试覆盖重复启动，不会重复增加列。
- API 测试覆盖请求体持久化、模型字段、语义组，以及 Admin 默认折叠行为。
- `cargo fmt --all -- --check`、`git diff --check` 通过。

## 结论

实现能够回答“服务端究竟向 Qwen 发送了什么”，且不会改变现有页面还原区的布局。审计内容适合调试和人工比对，但可能包含 OCR 原文，因此当前只放在既有 Admin 页面，不应直接暴露为公共接口。
