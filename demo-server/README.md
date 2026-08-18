# ImageTranslateOCR 自建翻译服务

该服务实现 Android 端的组级翻译协议：

```text
端侧 OCR -> 端侧初步语义组 -> POST /api/v3/translate/layout-plan
          -> 服务端高置信重组 -> Qwen 全文感知组级翻译
          -> sourceGroup/member 血缘 + 声明式布局计划
          -> Android 真实字体排版和回贴
```

服务使用 Axum、Diesel Async、SQLite，并支持本地 Qwen 或 OpenLux。数据库保存请求审计以及最近一批请求/响应 JSON，用于本地布局核验；默认最多保留 200 条，可通过 `REQUEST_HISTORY_LIMIT` 调整。选择 OpenLux 时 OCR 文本会发送到对应的线上模型；API Key 不进入审计记录。

Android Debug 版在“内录全屏采集”场景提供两个独立开关：可上传翻译前的 OCR 完整帧，也可在译文回贴成功并完成一帧绘制后上传实际屏幕。服务只保存图片用于人工前后对照，不对图片执行 OCR，也不把图片传给 Qwen。图片目录由 `REQUEST_IMAGE_DIR` 控制，审计 JSON 只保留图片元数据，不保存 Base64。图库、拍照、普通静态图片翻译、非 `LIVE_SCREEN` 请求和 Release 版都不会上传图片。

## 启动

```bash
# Terminal 1: keep the local runtime on 127.0.0.1:11434.
ollama serve

# Terminal 2: download once, then start Axum.
ollama pull qwen3.5:9b
cd demo-server
ollama create qwen3.5-translation:9b -f ollama/QwenTranslation.Modelfile
cp .env.example .env
# 局域网部署建议在 .env 中设置 SELF_HOSTED_BEARER_TOKEN
cargo run
```

默认监听 `0.0.0.0:8090`：

- 健康检查：`GET /healthz`
- 影子分组兼容：`POST /api/v2/translate/groups`
- 权威组与布局计划：`POST /api/v3/translate/layout-plan`
- Gemini 原生 V4 转发：`POST /api/v4/translate/gemini-native/layout-plan`
- 取消翻译：`POST /api/v2/translate/requests/{requestId}/cancel`
- 回贴后调试图：`POST /api/v3/translate/requests/{requestId}/rendered-capture` 或 `POST /api/v4/translate/requests/{requestId}/rendered-capture`
- 请求历史：`GET /admin/requests`
- 请求详情：`GET /admin/requests/{auditId}`
- 调试原图：`GET /admin/requests/{auditId}/image`
- 回贴后调试图：`GET /admin/requests/{auditId}/rendered-image`

历史页可以按 `SUCCEEDED`、`FAILED`、`CANCELLED` 和 v2/v3 过滤，并使用 `page`、`pageSize` 服务端分页；默认每页 20 条，页面可选 20/50/100 条。页面标题、统计、筛选和底部分页保持在列表外，中间表格独立滚动且表头固定。详情包含完整请求、翻译响应，以及按 viewport、OCR regions 和 `renderSlots` 重建的页面；“采集参考”默认显示翻译前原图，标题旁的复选框可在同一固定尺寸画框内切换端侧实际回贴截图，切换不会改变页面高度。点击历史记录会在新页面打开详情。若配置了 `SELF_HOSTED_BEARER_TOKEN`，浏览器访问管理页时使用任意用户名，并将该 token 作为 Basic Auth 密码；未配置 token 时管理页只适用于受信任的本地或局域网环境。

Android 在滚动、主动取消或协程终止时调用取消接口。服务端会中止对应的模型等待并把审计状态记录为 `CANCELLED`；取消接口使用 `requestId`，因此客户端每次实时识别请求必须使用唯一 ID。

可使用仓库内的 Xi's Time OCR 样本执行真实本地推理：

```bash
curl --fail-with-body \
  --header 'Content-Type: application/json' \
  --data-binary @- \
  http://127.0.0.1:8090/api/v3/translate/layout-plan \
  < <(jq '.schemaVersion = 3' examples/xi-news-request.json)
```

默认模型是基于本机 `qwen3.5:9b` 创建的 `qwen3.5-translation:9b`，地址为 `http://127.0.0.1:11434/v1`，不需要 API Key。该别名通过 Modelfile 把上下文窗口固定为 16384 tokens；直接使用基础模型时，Ollama 在可用显存不足 24 GiB 的设备上可能只分配 4096 tokens，整页请求会占满上下文并截断 JSON 输出。`QWEN_MAX_TOKENS` 控制单次翻译的最大输出，默认 4096。

服务也支持 OpenLux 的 OpenAI 兼容接口。两套配置互不覆盖：

```dotenv
TRANSLATION_PROVIDER=openlux

QWEN_BASE_URL=http://127.0.0.1:11434/v1
QWEN_API_KEY=
QWEN_MODEL=qwen3.5-translation:9b
QWEN_MODELS=qwen3.5-translation:9b,qwen3:4b

OPENLUX_BASE_URL=https://api.openlux.ai/v1
OPENLUX_API_KEY=<API Key>
OPENLUX_MODEL=gemini-3.5-flash-lite
OPENLUX_MODELS=gemini-3.5-flash-lite,gpt-4.1,claude-sonnet-4-6
```

`TRANSLATION_PROVIDER` 决定启动时默认使用 `qwen` 或 `openlux`。启动后可在
`/admin/requests` 页眉的“翻译 Provider / Model”控件中切换；切换只影响新请求，正在执行的请求继续使用
其开始时固定的 Provider 和模型。API Key 仅从环境变量读取，不会进入管理页、健康检查或请求审计。

Android 端侧也支持 Google Gemini 原生 `generateContent`：将 Provider 设置为 `google`，
Base URL 设置为 `https://generativelanguage.googleapis.com/v1beta`，并在设备高级设置中填写
独立的 Gemini API Key。原生请求使用 `generationConfig.thinkingConfig.thinkingLevel`；Key
通过 `x-goog-api-key` 请求头发送，并由 Android Keystore 加密保存，不写入 URL、日志或审计。
生产环境仍建议通过自有后端调用，避免长期服务端密钥分发到终端设备。

本地服务端也提供固定的 Gemini 原生 V4 转发入口。端侧向
`/api/v4/translate/gemini-native/layout-plan` 发送与普通 V4 相同的 OCR 请求；服务端执行
regions-first 分组，使用 `GEMINI_API_KEY` 调用原生 `generateContent`，再返回现有 V4 DSL。
原生请求采用 `responseMimeType=application/json`、`responseJsonSchema` 和 Gemini 3
`thinkingConfig.thinkingLevel`，与 `scripts/gemini_native_translation_validation.py` 的验证方式一致。
可通过 `GEMINI_PROXY_URL` 配置服务端出站代理；API Key 不由端侧上传，也不会写入审计。
若不需要代理，应将 `GEMINI_PROXY_URL` 留空。也可将 `TRANSLATION_PROVIDER=gemini-native`
设为全局默认；此时服务启动阶段会要求存在 Gemini API Key。

OpenLux 还可独立设置 `OPENLUX_REASONING_EFFORT`、`OPENLUX_MAX_TOKENS` 和
`OPENLUX_TIMEOUT_SECONDS`。当 `TRANSLATION_PROVIDER=openlux` 时，
`OPENLUX_API_KEY` 和 `OPENLUX_MODEL` 必填。

Provider 推理控制使用互斥配置：

```dotenv
OPENLUX_THINKING_MODE=thinking_level    # none | reasoning_effort | thinking_level
OPENLUX_THINKING_LEVEL=medium           # minimal | low | medium | high
```

`reasoning_effort` 会发送 OpenAI 兼容顶层字段；`thinking_level` 会发送
大写枚举值到 `google.thinking_config.thinking_level`。`extra_body` 只是 OpenAI SDK 的客户端参数名，
使用原始 HTTP 请求时不能把它作为 JSON 字段发送。两种推理控制不会同时出现。V4 请求可在
`translation.thinkingControlMode/thinkingLevel` 中覆盖该次调用，方便使用同一份 OCR
数据执行 A/B。Gemini 3 可验证两种写法；GPT-4.1 不发送推理字段；非 Gemini
模型不发送 Gemini `thinking_level`。Admin 详情页同时显示配置方式、
实际字段、级别和兼容回退状态。

`QWEN_MODELS` 和 `OPENLUX_MODELS` 是逗号分隔的模型白名单，`QWEN_MODEL` / `OPENLUX_MODEL`
是各 Provider 的启动默认模型且必须包含在对应白名单内。Admin 会为每个配置模型显示独立切换项。
调用方也可以仅对单次翻译请求增加以下请求头，不修改全局选择：

```http
X-Translation-Provider: openlux
X-Translation-Model: <OPENLUX_MODELS 中的模型 ID>
```

服务端拒绝未出现在配置白名单内的模型。请求历史“模型”列记录实际使用的
`provider:model`，响应的 `provider` 和 `modelVersion` 同样反映本次请求的真实选择。
当前 OpenLux 优先验证顺序为 `gemini-3.5-flash-lite`、`gpt-4.1`、
`claude-sonnet-4-6`；其中 Gemini 是 OpenLux 的默认模型，其他模型可在 Admin 页眉直接切换。

基础 Q4_K_M 模型包体约 6.6 GB，在 24 GB Apple Silicon 机器上用于质量优先的组级翻译。已安装的纯文本 `qwen3:4b` 可作为低资源回退，但应为它另建带足够 `num_ctx` 的 Modelfile；其包体约 2.5 GB，且真实新闻标题样本曾出现语义压缩错误。

`QWEN_BASE_URL` 接受任意本地 OpenAI 兼容 API 基址，也可以指向 llama.cpp、vLLM 或 MLX 网关。服务会追加 `/chat/completions`，并通过 `/models` 检查运行时和指定模型是否可用。`GET /healthz` 中三项状态含义如下：

- `modelConfigured`：模型地址和认证配置可用于请求。
- `modelReachable`：本地模型运行时正在响应。
- `modelAvailable`：`QWEN_MODEL` 对应模型已加载/安装。

生产环境中 Axum 可以监听局域网，但 Ollama 只需监听 `127.0.0.1`，不要把 `11434` 直接暴露给手机。

## 协议边界

- 客户端上传截图坐标空间、语义组和原始 OCR 行。
- 调试图片只允许出现在 `LIVE_SCREEN` 请求中，仅作为审计附件保存；服务布局仍以请求的原始 viewport 坐标为准。
- 回贴后图片必须匹配已成功请求的 `requestId/sessionId/generation/translationRevision`，不能挂接到失败、取消或其他轮次的记录。
- v2 返回端侧原组翻译和 `documentPlan.mode=SHADOW`，不会改变旧端回贴单位。
- v3 只对置信度不低于 0.90、血缘和几何可验证的候选使用服务端权威组。
- Qwen 只返回按 `groupId` 绑定的完整译文，不生成像素坐标。
- 服务端返回 `sourceGroupIds`、`memberRegionIds`、合并锚点和声明式 `layoutHint`。
- Android 校验 request/session/generation/revision 后，使用设备字体完成最终字号、换行、擦除与回贴。
- 原 Pnuts `/api/v1/translate/regions` 不由本服务替代，Android 可单独选择该兼容 Provider。
- Android Debug 可连接局域网 HTTP 地址；非 Debug 构建必须使用 HTTPS。
