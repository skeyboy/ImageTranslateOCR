# ImageTranslateOCR

Android 图片 OCR 文字识别 + 翻译 + 原图覆盖工具

## 功能
- 📷 选择相册图片
- 🖥️ Android 主动屏幕截取会话，通过常驻通知按需截图并翻译
- 📱 Android 后台监听、开机恢复，以及截图就绪后直接交给 App 完整处理
- 🖼️ 截图自动载入“原始图片”，连续执行 OCR、翻译、背景修复、文字重绘和区域打标
- 🔔 截图结果持续保留在通知栏，横幅收起后仍可查看、重译或保存
- ⚡ 图片加载后一键识别并翻译，低频参数按需展开
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

自动化测试：

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:connectedDebugAndroidTest
```

开启后台截图监听需要通知和完整图片访问权限。“检测到截图时显示悬浮窗”默认关闭；开启后才申请“在其他应用上层显示”权限，并在截图到达时显示左上角预览和操作。关闭时不会创建悬浮窗，而是把图片 URI 直接交给主界面的“原始图片”并自动执行完整翻译与打标；系统拒绝后台拉起界面时仅保留持久通知。监听不会持续采集屏幕内容。开启“开机自启”后，应用会在设备启动且既有权限仍有效时恢复监听；部分厂商仍可能要求在系统的自启动或省电设置中额外放行。

开启后台监听后，返回桌面或从最近任务划掉应用界面不会停止监听，常驻通知表示服务仍在运行。应用优先响应系统媒体变更事件；部分厂商在界面退出后不再分发该事件，因此服务还会每 750ms 查询一次最新截图元数据作为兜底，不持续读取屏幕。应用内或通知中的“停止”会真正关闭监听和恢复任务。Android 设置中的“强行停止”会禁止应用自行启动，必须由用户重新打开应用，这是系统级限制。

点击“开启屏幕翻译浮窗”只会显示一条可拖动的小型控制条，此时不会申请录屏权限，也不会采集屏幕。可在浮窗中选择“中英互译”“英 → 中”或“中 → 英”；点击浮窗或通知中的“识别”后才出现系统录屏授权，授权成功会自动执行第一次识别。识别前会短暂隐藏本应用浮层，从录屏帧中读取当前画面，经快速 OCR、翻译、局部背景修复和原文字体风格估算后，把译文贴片按原文位置显示在不可触摸的全屏透明层上，因此底层页面仍可正常滚动。页面滚动时旧译文会清除，画面停稳后自动重新识别；结果控制条会自动收起为底部居中的状态胶囊，自动刷新不会反复展开完整控制。取消录屏授权时浮窗仍会保留，可再次点击识别；点击“取消预览”会停止自动刷新并移除全屏译文层，只保留小浮窗；点击“结束”会释放录屏和悬浮窗资源。

## 调校记录

OCR、翻译、擦除和译文排版的历史问题、效果截图及提交记录见 [图片文字翻译与替换调校记录](docs/translation-tuning-log.md)。
