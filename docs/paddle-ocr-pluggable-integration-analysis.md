# PaddleOCR 端侧可插拔集成分析

## 结论

可行，当前已完成最小可用集成。参考项目的 OCR 能力已经封装为 Android AAR，核心是 Kotlin API、ONNX Runtime、OpenCV 和约 6 MB 的检测/识别模型；当前工程同为 Kotlin/Android，且已经使用 OpenCV，因此不存在平台或数据形态上的结构性阻塞。

默认引擎保持现有 ML Kit。用户可在悬浮窗“设置 -> OCR 识别引擎”中选择 ML Kit 或 PaddleOCR，选择持久化到 SharedPreferences。PaddleOCR 仅在首次实际识别时加载，切回 ML Kit 后释放 ONNX 会话。

## 架构与桥接

调用链如下：

```text
主界面 / 截图监听 / 连续悬浮窗
              |
         OCRManager 门面
              |
       OcrEngineSettings
         /            \
MlKitOcrEngine     PaddleOcrEngine
         \            /
          List<RecognizedText>
```

`OcrEngine` 是公用契约，保留当前工程需要的普通识别、快速识别、模型状态、模型准备和释放能力。`OCRManager` 保留原类名和调用方式，因此现有主界面、后台截图与连续识别流程无需感知具体实现。

Paddle 返回 `OCRResult(text, confidence, OCRBox)`，桥接映射为当前统一格式：

| Paddle 字段 | 当前字段 | 规则 |
| --- | --- | --- |
| `text` | `RecognizedText.text` | 去除首尾空白，过滤空结果 |
| `confidence` | `modelConfidence` | 限制到 0..1 |
| `confidence` | `consensusScore` | Paddle 单次识别无多 Pass 共识，直接使用模型置信度 |
| 四点 `OCRBox` | `Rect bounds` | 取外接矩形并裁剪到 Bitmap 范围 |
| 语言模式 | `recognizerScript` | 沿用调用方指定的 AUTO/中文/英文语义 |

当前渲染、擦除和翻译接口只消费矩形框，因此外接矩形兼容现有数据格式。代价是旋转文字会损失四边形精度；若后续需要旋转文本精确擦除，应把统一模型扩展为可选 polygon，而不是在桥接中丢弃后再反推。

## 依赖与制品

- `app/libs/ppocr-sdk-release.aar`：由参考项目 `:ppocr-sdk:assembleRelease` 生成，包含约 6 MB ONNX 模型。
- `com.microsoft.onnxruntime:onnxruntime-android:1.22.0`：AAR 的运行时依赖。
- OpenCV 复用当前工程已有依赖，不重复声明。
- AAR SHA-256：`56653a76da8623be14941262e700d0abf02020f4bdde5a808fca410720618828`。

参考 SDK 清单声明 minSdk 26，而当前 App 是 24。源码未发现显式 26+ API，ONNX Runtime 支持更低版本，所以当前清单对 `com.paddle.ocr` 做了定向 override 以保持既有覆盖面。发布前必须在 API 24/25 arm64 真机各跑一次识别冒烟；若不通过，应将 Paddle 选项限制到 API 26+，而不是让 App 崩溃。

## 行为与生命周期

- ML Kit 是默认值，升级安装且没有偏好值的用户行为不变。
- Paddle 模型内置，不显示 ML Kit 的“模型下载”子菜单，也不依赖 Google Play 服务。
- 语言选择接口保持一致；当前 Paddle 模型本身是统一识别模型，中文/英文模式主要用于下游脚本标记，不会切换 Paddle 模型。
- 连续识别仍复用同一个 `BackgroundTranslatedImageProcessor` 和 OCR 门面，避免每帧冷加载。
- 引擎切换会清理连续识别快照；下一次调用由门面关闭旧引擎并创建新引擎。

## 风险与验收

主要风险：Paddle 冷启动和峰值内存、API 24/25 兼容性、旋转框退化、快速连续截图下的延迟，以及 AAR 更新时依赖版本漂移。

建议验收矩阵：

1. API 24、25、26、34 各至少一台 arm64 设备，分别完成首次 Paddle 识别和来回切换。
2. 中文、英文、中英混排、密集小字、旋转文字各 20 张，与 ML Kit 比较召回率、误识率和框覆盖。
3. 悬浮窗连续滚动 10 分钟，记录 P50/P90/P99、峰值 PSS、温升和崩溃。
4. 杀进程重启后确认引擎选择保留；清除数据后确认恢复 ML Kit 默认值。
5. release 包执行 R8、安装、离线识别，确认 AAR 资产和 ONNX native 库完整。

上线建议先保持手动选择，不自动回退。若需要灰度，可按设备 API/ABI 隐藏 Paddle 选项；回滚只需默认/强制选择 ML Kit，无需改动上层 OCR 数据流。

## 更新方式

在参考项目执行：

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
  ./gradlew :ppocr-sdk:assembleRelease
```

用生成的 `ppocr-sdk-release.aar` 替换当前同名制品，更新 SHA-256，然后运行：

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
  ./gradlew :app:testDebugUnitTest :app:assembleDebug
```

参考 SDK 源码文件声明 Apache License 2.0。正式分发前仍应由项目方补齐第三方 NOTICE、模型许可和 ONNX Runtime/OpenCV 的归档审查。
