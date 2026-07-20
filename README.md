# ImageTranslateOCR

Android 图片 OCR 文字识别 + 翻译 + 原图覆盖工具

## 功能
- 📷 选择相册图片
- 🔍 ML Kit 中文 OCR 识别
- 🌐 ML Kit 翻译（中→英）
- 🎨 OpenCV Inpaint 擦除原文字
- ✍️ Canvas 绘制翻译结果
- 💾 保存到相册

## 技术栈
| 模块 | 方案 |
|------|------|
| OCR | Google ML Kit (中文) |
| 翻译 | Google ML Kit Translation |
| 擦除 | OpenCV Inpaint (Telea) |
| UI | Kotlin + ViewBinding |

## 构建

当前项目使用 Android Gradle Plugin 8.7.3、Gradle 8.9 和 JDK 21。命令行构建：

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:assembleDebug
```

## 调校记录

OCR、翻译、擦除和译文排版的历史问题、效果截图及提交记录见 [图片文字翻译与替换调校记录](docs/translation-tuning-log.md)。
