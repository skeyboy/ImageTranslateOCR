# ImageTranslateOCR iOS

与 Android 版本对应的 iOS 18+ SwiftUI 实现。

## 功能对应

| Android | iOS |
| --- | --- |
| ML Kit 中文 OCR | Google ML Kit Text Recognition v2 中文模型 |
| ML Kit Translation | Apple Translation framework |
| OpenCV Telea Inpaint | Core Graphics 背景采样擦除 |
| Canvas 译文覆盖 | `UIGraphicsImageRenderer` |
| 相册选择/保存 | PhotosUI / PhotoKit |

## 构建

```bash
pod install
xcodebuild -workspace ImageTranslateOCR.xcworkspace \
  -scheme ImageTranslateOCR \
  -sdk iphoneos \
  -configuration Debug \
  CODE_SIGNING_ALLOWED=NO build
```

请使用 `ImageTranslateOCR.xcworkspace` 打开工程，不要直接打开 `.xcodeproj`。OCR 依赖固定为官方文档当前版本 `GoogleMLKit/TextRecognitionChinese` 8.0.0，模型静态链接进应用。

该版本 ML Kit 的 CocoaPods 配置会在 Apple Silicon 上排除 `arm64` 模拟器架构，因此完整 OCR 流程需要在 iOS 18+ 真机验证；命令行仍可构建 `x86_64` 模拟器产物，但当前 arm64-only 模拟器无法安装它。

首次翻译时，系统可能提示下载中英翻译语言包。OCR、翻译语言包和相册保存请在 iOS 18+ 真机完成同步验证。
