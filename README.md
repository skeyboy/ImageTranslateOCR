# ImageTranslateOCR

Android 图片 OCR 文字识别 + 翻译 + 原图覆盖工具

## 功能
- 📷 选择相册图片
- 🖥️ Android 主动屏幕截取会话，通过常驻通知按需截图并翻译
- 📱 Android 后台监听、开机恢复，以及截图就绪后直接交给 App 完整处理
- 🖼️ 截图自动载入“原始图片”，连续执行 OCR、翻译、背景修复、文字重绘和区域打标
- 🔔 截图结果持续保留在通知栏，横幅收起后仍可查看、重译或保存
- ⚡ 图片加载后一键识别并翻译，低频参数按需展开
- 🔍 ML Kit 中英文 OCR 识别（模型按需下载）
- 🌐 ML Kit 翻译（中→英）
- 🎨 OpenCV Inpaint 擦除原文字
- ✍️ Canvas 绘制翻译结果
- 💾 保存到相册

## 技术栈
| 模块 | 方案 |
|------|------|
| OCR | Google ML Kit（中英文，Google Play services 按需模块） |
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

真实 OCR 与翻译语义冒烟门可单独运行；报告会写入设备应用目录的 `files/benchmark/semantic-quality.json`：

```bash
ANDROID_SERIAL=<device-serial> JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
  ./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.example.imagetranslate.screenshot.LiveSemanticGoldenBenchmarkTest
```

连接实机后，可对同一稳定视口执行差分/分块与整屏识别 A/B。脚本会固定页面和滑动手势、交替执行顺序，并输出单行 JSON 与 P50/P90 汇总：

```bash
AB_RUNS=4 ANDROID_SERIAL=<device-serial> scripts/live-recognition-ab-batch.sh
AB_CANDIDATE=VERTICAL_BANDS ANDROID_SERIAL=<device-serial> scripts/live-recognition-ab.sh
```

开启后台截图监听需要通知和完整图片访问权限。“检测到截图时显示悬浮窗”默认关闭；开启后才申请“在其他应用上层显示”权限，并在截图到达时显示左上角预览和操作。关闭时不会创建悬浮窗，而是把图片 URI 直接交给主界面的“原始图片”并自动执行完整翻译与打标；系统拒绝后台拉起界面时仅保留持久通知。监听不会持续采集屏幕内容。开启“开机自启”后，应用会在设备启动且既有权限仍有效时恢复监听；部分厂商仍可能要求在系统的自启动或省电设置中额外放行。

开启后台监听后，返回桌面或从最近任务划掉应用界面不会停止监听，常驻通知表示服务仍在运行。应用优先响应系统媒体变更事件；部分厂商在界面退出后不再分发该事件，因此服务还会每 750ms 查询一次最新截图元数据作为兜底，不持续读取屏幕。应用内或通知中的“停止”会真正关闭监听和恢复任务。Android 设置中的“强行停止”会禁止应用自行启动，必须由用户重新打开应用，这是系统级限制。

点击“开启识别悬浮窗”后，已有悬浮窗权限时会直接显示可拖动的“录屏识别”状态胶囊，不需要授权或点击系统通知；首次使用只需完成“显示在其他应用上层”授权，通知仅用于维持前台服务。点击胶囊后会在原位置展开控制条，可先选择“中英互译”“英 → 中”或“中 → 英”。识别策略设置中可独立选择 OCR 自动识别、仅中文或仅英文，并查看或下载对应 OCR 模型；未下载的必需模型也会在首次识别前自动准备。再点击蓝色“开始识别”进入系统录屏授权，授权成功会自动执行第一次识别。识别前会短暂隐藏本应用浮层，从录屏帧中读取当前画面，经快速 OCR、翻译、局部背景修复和原文字体风格估算后，把带自适应明暗毛玻璃底层的译文贴片按原文位置显示在全屏透明层上。翻译窗口使用低于系统遮挡阈值的透明度并设置为不可触摸，点击、滑动和滚动会继续传递给底层页面。结果展示时底层页面会弱模糊并轻度降噪，贴片只保留圆角译文材质区域，减少原文与译文混杂。翻译层始终位于控制浮窗下方，不会遮住控制条。页面滚动时旧译文会清除，画面停稳后自动重新识别；结果控制条会在当前位置收起为状态胶囊，自动刷新不会反复展开完整控制，展开态与收起态均可拖动且切换时保持同一视觉中心。取消录屏授权时浮窗仍会保留，可再次点击“开始识别”；点击“取消预览”会停止自动刷新并移除全屏译文层，只保留小浮窗；点击“结束”会释放录屏和悬浮窗资源。

## 调校记录

- 实时录屏识别、差分、缓冲、切分、悬浮交互、性能数据和 Git 实验节点见 [实时屏幕识别与悬浮翻译实验账本](docs/live-screen-translation-experiment-log.md)。
- OCR、翻译、擦除和译文排版的历史问题、效果截图及提交记录见 [图片文字翻译与替换调校记录](docs/translation-tuning-log.md)。
- 系统截图监听、主动截屏入口和平台权限边界见 [OCR 与截图识别完整报告](docs/ocr-and-screenshot-recognition-complete-report.md)。
- 可选、离线优先的智能复核、上下文增强和外部自动操作实验路线见 [离线优先智能辅助规划实施文档](docs/offline-smart-assist-implementation-plan.md)。
- 已完成的 Library First M0/M1 范围、测试和性能基线见 [Smart Assist Core M0/M1 实施报告](docs/smart-assist-core-m0-m1-implementation-report.md)。
- 悬浮窗“离线智能辅助”开关、宿主 Adapter 和回退行为见 [离线智能辅助 App 接入报告](docs/smart-assist-app-integration-report.md)。
- Google 式屏幕翻译能力边界、语义黄金门、Track 缓存和自适应背景实测见 [实时屏幕识别与悬浮翻译实验账本](docs/live-screen-translation-experiment-log.md#2026-07-27google-式屏幕翻译优化语义门与-track-缓存)。

后续修改实时屏幕识别链路时，必须同步更新实验账本，记录基线 Git、实验代码 Git、设备与配置、自动化和实机数据、证据路径以及保留/部分保留/回退结论。
