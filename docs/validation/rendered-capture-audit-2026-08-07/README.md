# 端侧回贴后屏幕采集与 Web 前后对照验证

## 目标

在 Android Debug 版的内录全屏翻译场景中，允许用户独立开启“回贴后截图上传”。当自建 v3 翻译成功且译文 overlay 已完成绘制后，端侧再采集一帧实际屏幕，上传到同一次服务端请求记录。Admin 详情页可直接对比翻译前 OCR 原图和端侧真实回贴结果。

## 端侧实现

- 新增独立开关“调试：上传译文回贴后截图”，不复用“上传本次 OCR 截图”的设置。
- 仅 `BuildConfig.DEBUG + SELF_HOSTED + LIVE_SCREEN` 的成功结果可进入该链路。
- v3 请求返回后保留 `requestId/sessionId/generation/translationRevision`；只有实际生成 patch 的远端结果才携带追踪信息。
- overlay 的 `onPresented` 回调后等待 280ms，再从仍在运行的 MediaProjection/ImageReader 获取一帧，不隐藏译文层。
- 一帧对应多个服务端 v3 请求时，编码一次并关联上传到每个请求；滚动、取消、代次变化或切换后端会丢弃待上传任务。
- 图片沿用调试 JPEG 编码策略，最大边 1080px、质量 72，上传失败只记录日志，不影响已经完成的回贴。

## 服务端实现

- 新增 `POST /api/v3/translate/requests/{requestId}/rendered-capture`。
- 请求必须匹配状态为 `SUCCEEDED` 的 `requestId/sessionId/generation`，并再次校验 `translationRevision` 和 `scene=LIVE_SCREEN`。
- 回贴后图片使用独立 `rendered_request_images` 表，不覆盖翻译前原图；重复上传同一记录时替换旧后图。
- 图片 Base64 不写入审计 JSON，也不进入 OCR、布局规划或 Qwen 请求。
- Admin 详情页在布局还原区上方新增“端侧回贴前后对照”；桌面双列，手机单列。

上传体示例：

```json
{
  "sessionId": "semantic-session-id",
  "generation": 12,
  "translationRevision": 2,
  "capture": {
    "mimeType": "image/jpeg",
    "dataBase64": "...",
    "pixelWidth": 486,
    "pixelHeight": 1080
  }
}
```

## 验证结果

- Rust：15 个单元测试与 7 个 API 测试通过。API 测试覆盖成功翻译、回贴后图片上传、数据库关联、Admin 对照文案及图片读取。
- Android：184 个 Debug JVM 测试通过，`assembleDebug` 通过。
- APK：`app/build/outputs/apk/debug/app-debug.apk`，SHA-256 `b04a6a1f9c64f48b39d7e6e6ecbc8545ad8e582a73218b06e804d5428166629e`。
- Web 实图：桌面 1440px 下对照面板为 `649px + 649px` 双列；手机 390px 下为 351px 单列；两种尺寸均无横向溢出，浏览器控制台 0 错误。
- 实图元数据：翻译前图片 365x843、154936 bytes；回贴后图片 367x847、107773 bytes，服务端图片读取接口返回正确 `image/png`。

## 结论

该链路满足“服务端规划视图”和“端侧真实绘制结果”分离核验。服务端记录中的后图来自 overlay 已呈现后的 MediaProjection 实际帧，因此可以直接发现字体、槽位、擦除背景、漏贴、越界和悬浮控件遮挡等仅在 Android 绘制阶段出现的问题；请求四元组校验避免了滚动或并发请求下的错误关联。
