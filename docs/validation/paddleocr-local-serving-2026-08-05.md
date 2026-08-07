# PaddleOCR 本地服务实测（2026-08-05）

## 结论

PaddleOCR 可以在当前 Apple Silicon M4 主机上以 CPU 模式完成本地 HTTP 部署。`/health` 与 `/ocr` 均已验证可用，默认 PP-OCRv6 medium pipeline 能正确返回文本、置信度和坐标。

默认 medium 模型不适合作为 Android 实时逐帧 OCR 的替代方案：普通截图热请求约 4.3 秒，完整手机屏幕约 8.3 秒，均显著慢于项目已有端侧 OCR。裁剪到小 ROI 后典型耗时约 1.3 秒，服务端更适合作为低置信度 ROI 复核、复杂版面识别或非实时批处理能力。

## 环境

- 项目版本：`6f131e01566354bd3f1128d4f34c3bba6f961c56`（测试时工作区存在未提交改动）
- 主机：Apple Silicon `arm64`，24 GB 内存
- Python：3.9.6，隔离虚拟环境
- PaddlePaddle：3.2.1，CPU
- PaddleOCR：3.7.0
- PaddleX：3.7.2
- 服务地址：`http://127.0.0.1:8081`
- 接口：`GET /health`、`POST /ocr`

安装后的虚拟环境约 770 MiB，首次启动下载的官方模型约 190 MiB。服务加载完成后观察到 RSS 约 1.58 GiB，连续测试结束后空闲 RSS 约 1.04 GiB。

默认 pipeline 加载以下模型：

- `PP-LCNet_x1_0_doc_ori`
- `UVDoc`
- `PP-LCNet_x1_0_textline_ori`
- `PP-OCRv6_medium_det`
- `PP-OCRv6_medium_rec`

## 请求配置

测试关闭了文档方向分类、文档展平、文本行方向分类和结果可视化，检测最长边限制为 1280：

```json
{
  "fileType": 1,
  "useDocOrientationClassify": false,
  "useDocUnwarping": false,
  "useTextlineOrientation": false,
  "textDetLimitSideLen": 1280,
  "textDetLimitType": "max",
  "returnWordBox": false,
  "visualize": false
}
```

`file` 使用 Base64。上传体积包含 JSON 和 Base64 膨胀，正式 Android 接入应由项目 Gateway 改为 multipart，或只上传 ROI。

## 实测结果

| 场景 | 输入尺寸 | PNG 大小 | 上传大小 | 响应大小 | 总耗时 | 文本数 |
|---|---:|---:|---:|---:|---:|---:|
| 小 ROI 初次测量 | 447×152 | 31.6 KB | 42.4 KB | 1.3 KB | 1.275 s | 6 |
| 普通截图首次推理 | 466×930 | 143.5 KB | 191.6 KB | 4.4 KB | 4.314 s | 29 |
| 普通截图热请求 1 | 466×930 | 143.5 KB | 191.6 KB | 4.4 KB | 4.272 s | 29 |
| 普通截图热请求 2 | 466×930 | 143.5 KB | 191.6 KB | 4.4 KB | 4.521 s | 29 |
| 普通截图热请求 3 | 466×930 | 143.5 KB | 191.6 KB | 4.4 KB | 4.332 s | 29 |
| 完整手机屏幕 | 1440×3200 | 533.3 KB | 711.3 KB | 6.7 KB | 8.293 s | 37 |

普通截图三次热请求平均耗时 4.375 秒，中位数 4.332 秒。小 ROI 另做五次连续热请求，耗时分别为 1.241、1.247、1.330、1.358、1.324 秒，平均 1.300 秒，中位数 1.324 秒；独立脚本验收时另观察到一次 1.795 秒请求，后续应使用更多样本统计 P95。

完整手机屏幕结果平均识别置信度约 0.979，但该数值只是模型自评，不能替代 CER、检测召回率和框坐标 IoU 的人工标注评估。

### 20 张连续帧稳定性基准

使用 `docs/validation/feathered-material-rendering-2026-07-29/run/thumbs/001-source.jpg` 到 `020-source.jpg` 的 20 张 `240×320` 连续帧进行顺序请求：

- HTTP 和业务成功率：20/20
- 最小值：1.843 秒
- P50：2.323 秒
- P90：3.328 秒
- P95：3.378 秒
- 最大值：3.971 秒
- 平均值：2.477 秒
- 平均文本数：9

逐样本原始数据见 `docs/validation/paddleocr-local-serving-2026-08-05.batch-20.json`。这组图片来自同一连续场景且经过缩略，只能验证服务稳定性和延迟分布，不能用来判断跨语言、复杂版面或压缩失真的识别准确率。

测试图片 SHA-256：

- `docs/assets/2026-07-21-control-source.png`: `d38b2700de2bf5db874564a63eee83e8a0349694ede0e3c6cdb361179905e78b`
- `docs/assets/2026-07-21-source.png`: `7cb5dffd3036f15acebd40bb32abfb4f06e2a3b96a399d3bdbc4f02dbfd0bec5`
- `docs/validation/live-full-page-background-2026-07-28/optimized/light/current.png`: `6999db3e4e3f42e2b9f2a1d17412ae8a85e51b03eaeba3e16bccee5637897d02`

## 复现

```bash
scripts/setup-local-paddle-ocr-service.sh
scripts/run-local-paddle-ocr-service.sh
```

另一个终端执行：

```bash
scripts/verify-local-paddle-ocr-service.sh \
  docs/assets/2026-07-21-control-source.png
```

服务默认只绑定 `127.0.0.1`。在增加鉴权、请求大小限制、超时、并发限制和原图日志禁用策略前，不应将 PaddleX 原始端口直接暴露到局域网或公网。

## 下一阶段门槛

1. 使用 20～50 张相同原始截图，统一对比 ML Kit、端侧 PaddleOCR 和本地服务的 CER、检测召回率、框 IoU、P50/P95 和峰值内存。
2. 优先验证 PP-OCRv6 small/tiny 或裁剪 ROI，目标是显著降低当前约 1.3 秒的小 ROI 典型延迟。
3. 只有服务端识别质量能覆盖端侧失败样本时，才增加 `RemoteOcrEngine`；默认链路继续端侧执行。
4. Android 接入时保留 `generation`、原图尺寸、ROI 坐标和取消语义，旧请求结果不得回贴到新画面。

## 2026-08-05 一体化接入进展

上述第 4 项已实现：Android 新增可选的一体化引擎，经 Rust Gateway 调用 PaddleX 和 Hy-MT2，返回带 `sessionId`、`generation`、原文、译文、置信度和坐标的区域结果。Android 上传前将长边限制为 1600px 并使用 WebP，返回框映射回采集图坐标；PaddleX 原始端口仍仅监听 loopback。

小 ROI 的真实一体化请求总耗时 2051ms，其中 OCR 1244ms、翻译 807ms。实现与风险记录见 `docs/paddle-network-integrated-engine-implementation.md`。
