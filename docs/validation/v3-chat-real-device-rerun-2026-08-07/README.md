# v3 聊天截图真机复验

日期：2026-08-07  
设备：`23113RKC6C`（ADB `192.168.0.46:43107`）  
服务端记录：<http://192.168.0.63:8090/admin/requests/54bf88fa-4640-45b6-9b83-76145b9dd16f>

## 验证方式

附件以原始 `486 x 1080` 图片写入真机 MediaStore，通过主程序的截图翻译入口执行完整生产链路：

```text
真机 ML Kit OCR
  -> 端侧通用语义分组
  -> POST /api/v3/translate/layout-plan
  -> qwen3.5:9b 整组翻译
  -> Android ShapeAwareTextLayout
  -> 真机保存最终处理图
```

首次请求 `6ca1170d-a287-42d5-b4d6-174e3122ca74` 与遗留的全屏采集会话并行，虽然静态请求成功，但屏幕证据被浏览器悬浮回贴干扰。本报告采用清理旧采集会话后的独立复验记录 `54bf88fa-4640-45b6-9b83-76145b9dd16f`，不使用受干扰截图判断结果。

安装包信息：

```text
versionName=1.0
versionCode=1
device lastUpdateTime=2026-08-07 13:39:46
APK sha256=1b59f727ed15ad203c02574cbda79dc3d0c012ae9de23183a2fc74b95d169bd5
```

## OCR 与分组

```text
scene=STATIC_IMAGE
viewport=486 x 1080
OCR regions=29
client semantic groups=13
input characters=680
```

17 行聊天正文被端侧完整划入一个 `BODY` 组：

```text
groupId=semantic-7595f3d7
memberRegionIds=17
sourceLineCount=17
groupingConfidence=0.82
bounds=(68,362)-(407,796)
layoutShape=RECT
renderSlots=[(68,362)-(407,796)]
```

OCR 原文：

```text
The Web3 industry tried and we did
quite well.Until the country decided
to invest in something that did not
fit intothe country nor the Web3
community.Then all hell broke
loose.The rest is history.After that,
we were suddenly All in AI,that was
really dangerous and riskier than
Web3.Gladthat recently we are
Coming back to our senses.Now,
the few solid Web3 projects in the
US are from Singapore.Indeed,
these youngsters have made us
proud.They left for a good reason,
and they will come back for sure
because this place is good for those
who have made it.
```

分组正确，但 OCR 仍产生了 `MO 99)`、`22AELË`、`Feux Lee E instagram:`、`Instagram Eüi` 等状态栏或混合语言区域噪声，其中部分被错误标为普通 `BODY`。

## 服务端 v3 输出

```text
status=SUCCEEDED
provider=self-hosted-qwen-layout-plan-v3
model=qwen3.5:9b
prompt=semantic-translation-qwen-v4-geometry-safe-output
durationMs=36340
clientGroups=13 -> plannedGroups=13
mergedGroups=0
authoritativeEligibleGroups=12
translatedGroups=5
preservedGroups=8
failedGroups=0
```

长正文保持一对一整组翻译，没有逐行降级。服务端译文：

```text
Web3 行业曾尝试过，我们也做得相当不错。直到该国决定投资一些既不符合国家利益也不符合 Web3 社区的项目时，一切才彻底改变。随后局势失控，其余皆为历史。在那之后，我们突然全面转向人工智能（AI），那真的非常危险且风险比 Web3 更高。很高兴最近我们正在重新回归理智。如今，美国仅有的几个扎实的 Web3 项目均来自新加坡。确实，这些年轻人让我们感到自豪。他们离开是有正当理由的，而他们肯定会回来的，因为这个地方对于那些成功的人来说是理想的所在。
```

布局提示：

```text
preferredMaxLines=17
minimumTextScale=0.86
maximumTextScale=1.0
lineSpacingMultiplier=0.92
alignment=START
overflowStrategy=REFLOW_THEN_SCALE_THEN_MORE
allowMore=true
```

## 真机回贴结果

主程序状态：

```text
完成，共替换 2 段文字；3 段翻译失败
```

正文结果符合预期：完整中文在原气泡矩形内回流为 15 行，未越过气泡左右边界，末句“所在。”可见；右下角 `22:43`、发送者、电话号码和下方消息没有被覆盖。空间足够，因此未触发压缩后的“更多”兜底。

页面整体仍存在一个可见缺陷。服务端把四个短 OCR 噪声原样标记成 `TRANSLATED`；端侧目标语言校验拒绝后尝试本地兜底，其中 `MO 99)` 被本地模型输出为“莫99)”并作为第一个替换区域写入，导致右上角网络/电量图标区域被擦除。另三个短噪声兜底失败，按安全策略保留原图。这解释了服务端的“5 个翻译、0 个失败”和端侧的“2 个替换、3 个失败”之间的差异。

## 结论

**长正文主链路通过，整页质量有条件通过。** 本样本验证了当前 v3 能把 17 行聊天正文作为单个翻译单元传输、翻译并在原气泡内完整回贴，之前的 `preferredMaxLines=6` 问题没有复现；服务端几何提示也被 Android 正确消费。

当前首要问题不是正文分组或布局，而是短 OCR 噪声的翻译资格判定：

1. 服务端不得把与原文相同、且不满足目标语言特征的内容标为 `TRANSLATED`，应返回 `PRESERVED` 或 `FAILED`。
2. 端侧需对状态栏、极窄单行、数字/图标混合乱码增加 `UI_CHROME` 或等价保护，禁止进入本地翻译兜底。
3. `authoritativeEligible` 不能只代表几何分组可信；还需单独表达 `translationEligible`，避免把几何置信度误当成文本可翻译性。
4. 保留当前长正文策略：远端失败时整组保留原文，禁止降级为逐行译文。

## 证据

- `source.png`：本轮附件原图。
- `translated-output.jpg`：真机“保存”导出的最终 486×1080 处理图，不含 Activity UI 和调试标号。
- `clean-static-result-body.png`：真机结果页正文区域截图。
- `clean-static-result-page.png`：真机结果状态与 v3 配置截图。
- `clean-server-record.json`：独立复验的完整请求、服务计划和响应。
- `server-record.json`：首次受并行全屏采集干扰但成功完成的静态请求记录。
