# ImageTranslateOCR 自建翻译服务

该服务实现 Android 端的组级翻译协议：

```text
端侧 OCR -> 端侧初步语义组 -> POST /api/v3/translate/layout-plan
          -> 服务端高置信重组 -> Qwen 全文感知组级翻译
          -> sourceGroup/member 血缘 + 声明式布局计划
          -> Android 真实字体排版和回贴
```

服务使用 Axum、Diesel Async、SQLite 和本地 Qwen。数据库保存请求审计以及最近一批请求/响应 JSON，用于本地布局核验；默认最多保留 200 条，可通过 `REQUEST_HISTORY_LIMIT` 调整。OCR 文本不会发送到线上模型服务。

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
- 取消翻译：`POST /api/v2/translate/requests/{requestId}/cancel`
- 回贴后调试图：`POST /api/v3/translate/requests/{requestId}/rendered-capture`
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
