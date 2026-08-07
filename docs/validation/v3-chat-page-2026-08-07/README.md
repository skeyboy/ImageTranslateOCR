# v3 聊天页真机验证

## 验证范围

- 为 admin 历史和详情增加 v2/v3 标注；历史页支持状态与版本组合过滤。
- 将既有 v2 记录 `51c72c83-884d-4e08-a5bc-4589329dc2bb` 的结构化请求无损重放到 v3。
- 使用附件聊天截图在真机 `23113RKC6C` 上执行 ML Kit OCR、端侧语义分组、自建 v3/Qwen 翻译和调试图片上传。
- 构建、安装 Debug APK 与测试 APK，并启动主界面检查安装结果。

## 既有 v2 记录的 v3 重放

原记录：

```text
auditId=51c72c83-884d-4e08-a5bc-4589329dc2bb
schemaVersion=2
clientGroups=14
ocrRegions=30
```

重放时只修改 `schemaVersion/requestId/generation/translationRevision`，移除审计 JSON 中不可重放的调试图片正文；OCR 文本、组、region、阅读顺序和几何不变。

输出：

```text
auditId=710b380f-9f42-4341-a9e0-e2cc6a8f6617
requestId=replay-v3-51c72c83-20260807
schemaVersion=3
documentPlan.mode=AUTHORITATIVE
clientGroups=14 -> plannedGroups=14
mergedGroups=0
translatedGroups=3
preservedGroups=11
durationMs=29844
```

17 行长正文保持一个 BODY 组，`groupingConfidence=0.82`、`authoritativeEligible=false`，未被错误跨组合并；译文仍按原组完成，返回 `sourceLineCount=17`、`preferredMaxLines=17`、`lineSpacingMultiplier=0.92`、`allowMore=true`。

管理页：<http://192.168.0.63:8090/admin/requests/710b380f-9f42-4341-a9e0-e2cc6a8f6617>

## 附件真机请求

```text
device=23113RKC6C
source=486 x 1080
OCR engine=ML_KIT
OCR elapsedMs=1605
OCR regions=29
filtered regions=29
semantic groups=13
```

服务端结果：

```text
auditId=2c35970b-4f9a-451d-aa40-1c614b2a3326
schemaVersion=3
documentPlan.mode=AUTHORITATIVE
clientGroups=13 -> plannedGroups=13
mergedGroups=0
authoritativeEligibleGroups=12
translatedGroups=4
preservedGroups=9
durationMs=35335
debugImage=486 x 1080 JPEG, 93271 bytes
```

长正文结果：

```text
memberRegions=17
sourceLineCount=17
groupingConfidence=0.82
authoritativeEligible=false
preferredMaxLines=17
minimumTextScale=0.86
lineSpacingMultiplier=0.92
overflowStrategy=REFLOW_THEN_SCALE_THEN_MORE
allowMore=true
```

整组译文：

```text
Web3 行业曾尝试过，我们也做得相当不错。直到该国决定投资一些既不符合国家利益也不符合 Web3 社区的项目时，一切才彻底失控。剩下的都是历史了。在那之后，我们突然全部转向人工智能（AI），那真的非常危险且风险比 Web3 更大。很高兴最近我们正在回归理智。如今，美国仅有的几个扎实的 Web3 项目都来自新加坡。确实，这些年轻人让我们感到骄傲。他们离开是有正当理由的，而他们肯定会回来的，因为这个地方对于那些成功的人来说是极好的地方。
```

Android 最终接收 10 个有效结果；`MD HD 99`、`Feux Lee t instagram:`、`Instagram Fii` 三个短残缺文本被服务端原样标记为译文，端侧因目标中文校验不通过而拒绝，随后本地翻译也未产生有效结果，最终安全保留原文。长正文成功，不存在碎片化回退。

真机专项输出：

```text
Time: 37.53s
OK (1 test)
translationElapsedMs=35812
resultCount=13
succeededCount=10
failedCount=3
longBodyCount=1
succeededLongBodyCount=1
```

管理页：<http://192.168.0.63:8090/admin/requests/2c35970b-4f9a-451d-aa40-1c614b2a3326>

## Admin 验证

- 当前历史记录：v2 18 条、v3 4 条。
- `?version=2` 和 `?version=3` 均只返回对应版本；状态过滤可与版本过滤组合。
- 列表新增“版本”列，详情顶部和元数据区均显示 v2/v3。
- 真机记录的调试原图自然尺寸为 486×1080，29 个 OCR region、13 个服务计划槽和13个译文槽均可回显。
- 桌面布局无页面级横向溢出；390×844 下四个布局面板和请求/响应面板均为单列，仍无横向溢出。
- 真机详情摘要：`AUTHORITATIVE 13→13组 · COMPACT 12 · FULL 1`。

## 构建与安装

```text
Android JVM tests=186, failures=0
Rust unit/API tests=19, failures=0
instrumentation tests=1, failures=0
APK versionCode=1 versionName=1.0
device lastUpdateTime=2026-08-07 11:08:34
```

APK：`app/build/outputs/apk/debug/app-debug.apk`

```text
size=220 MiB
sha256=594e70911ccc1aef2ece61c662fb3c874a6c387c6f71139ce632975d6fe428ae
```

本机默认 Java 11 不满足 Android Gradle 插件要求。本轮仅为构建命令临时使用 Android Studio JBR 21，没有修改项目、IDE 或系统 Java 配置。

## 证据文件

- `source.png`：本次附件原图。
- `self-hosted-actual-page.json`：真机 OCR、过滤、分组、翻译和布局提示完整输出。
- `app-installed.png`：APK 安装后主界面截图。

## 结论

本轮验证通过。admin 的版本标注和过滤有效；既有 v2 数据可无损重放到 v3；真实附件通过真机生成了新的 v3 请求并成功完成长正文整组翻译。服务端对 0.82 低置信长组保持保守一对一计划，端侧继续对短残缺伪译文执行安全拒绝。当前剩余问题不是 v3 通信或长组布局，而是应在服务端进一步把无法翻译的短 UI 噪声识别为 PRESERVED，减少端侧收到后再拒绝的无效结果。
