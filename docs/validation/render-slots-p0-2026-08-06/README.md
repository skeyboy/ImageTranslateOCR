# 绕图语义组、RenderSlots 与长正文安全回贴验证

验证日期：2026-08-06  
真机：23113RKC6C（Android，ADB `192.168.0.46:42087`）  
服务：Rust Axum + 本地 `qwen3.5:9b`，`http://192.168.0.63:8090`  
图片：新闻页 387 x 892、聊天页 1080 x 2400

## 本批实施范围

本批把以下四项合并实现并保留独立门控：

1. 根据行高、垂直连续性、水平覆盖和正文通道恢复绕图正文，不再依赖单一左边界。
2. 端侧根据 OCR 成员实际占用形状生成 `renderSlots`；服务校验、还原并原样返回，端侧拒绝越界或被服务改写的槽位。
3. 译文在槽内按“完整回流 -> 有限字号/行距压缩 -> 更多 -> 保留原文”排版；不同语义组的补丁禁止合并。
4. 自建 Qwen 失败时，长 `BODY` 整组保留原文，禁止转为端侧逐行/逐片翻译。

同时增加了保守 OCR 纠错（仅币种上下文中的 `CS -> C$`、`USS -> US$`）、模型协议残片清洗、AIMS 标识符空格漂移修复，以及电话号码、裸域名、网络状态、截断标题、品牌和发送者元数据的保留分类。

## 新闻页正常请求与响应

真机 OCR 识别 29 个区域，耗时 1531ms；内容过滤保留 22 个、排除 7 个，生成 7 个语义组。对比改造前只保留 16 个区域，绕图首段由 4/10 行恢复为 10/10 行。

首段请求的关键结构如下：

```text
role=BODY
translationUnit=GROUP
layoutShape=FLOW_SLOTS
memberRegionCount=10
sourceLineCount=10
renderSlots=
  [176,461,373,511]
  [130,517,373,569]
  [7,573,373,642]
```

发送前原始 OCR 与可审计纠错：

```text
raw:  has awarded CS20 million
send: has awarded C$20 million
code: CURRENCY_SYMBOL_RECOVERY

raw:  (USS19.4million)over the
send: (US$19.4million)over the
code: CURRENCY_SYMBOL_RECOVERY
```

整组请求原文：

```text
The Canadian government
has awarded C$20 million
(US$19.4million)over the
next four years to five centres of the
African Institute for Mathematical
Sciences.The centres are spread
across the continent and run through the AIMS-Next
Einstein Initiative.They will train talented young
African postgraduate rescarchers in mathematical
sciences.
```

Qwen 组级响应：

```text
加拿大政府已拨款 2,000 万加元（合 1940 万美元），在未来四年内资助非洲数学科学研究所的五个中心。这些中心遍布整个大陆，由 AIMS-Next 爱因斯坦计划运营。它们将培训有才华的年轻非洲研究生从事数学科学研究工作。
```

服务返回 `preferredMaxLines=10`、`sourceLineCount=10`、`layoutShape=FLOW_SLOTS`，三个 `renderSlots` 与请求完全相同。实际 Android 排版结果：

```text
outcome=FULL
displayedCharacterCount=113
textSizePx=13.984375
lineSpacingMultiplier=1.0
segments=3
segmentLines=2,2,2
segmentsOutsideRenderSlots=0
```

首次排版验证曾只使用第一个槽并错误进入“更多”。根因是 `StaticLayout.setMaxLines()` 未生成可消费的局部文本。修复后每个槽二分查找可完整绘制的最大文本前缀，再推进全文游标，最终三槽全部参与且不需要缩放或“更多”。

新闻页最终翻译耗时 41455ms，7/7 组成功，2/2 个长正文成功。标题两行合并为一个 `TITLE`，输出为 `AFRICA-CANADA：助力下一代爱因斯坦中心`；作者与 `Iweet Whatsapp` 分别按 `METADATA`、`CONTROL` 原样保留。第二段正确保留 `AIMS/AIMS-Next`，并使用术语“圆周理论物理研究所”。最终响应不存在此前的 `”},{`、`.user:{` 等协议残片。

## 聊天页正常请求与响应

真机 OCR 识别 31 个区域，耗时 1478ms；生成 14 个语义组。电话号码、裸域名、网络状态、截断标题、Instagram 品牌和与电话号码同排的发送者被识别为不可翻译结构。

长气泡请求为一个完整语义组：

```text
role=BODY
translationUnit=GROUP
memberRegionCount=17
bounds=[153,807,905,1769]
layoutShape=RECT
renderSlots=[153,807,905,1769]
```

整组 OCR 原文：

```text
The Web3 industry tried and we did
quite well.Until the country decided
to invest in something that did not
fit into the country nor the Web3
community.Then all hell broke
loose.The rest is history.After that,
we were suddenly All in AI,that was
really dangerous and riskier than
Web3.Glad that recently we are
Coming back to our senses.Now,
the few solid Web3 projects in the
US are from Singapore.Indeed,
these youngsters have made us
proud.They left for good reason,
and they will come back for sure
because this place is good for those
who have made it.
```

Qwen 组级响应：

```text
Web3行业曾尝试过，我们也做得相当不错。直到该国决定投资一些既不符合国家利益也不符合 Web3社区的项目时，一切才彻底失控。剩下的都是历史了。在那之后，我们突然全部转向 AI，那真的很危险，风险比 Web3 还要大。很高兴最近我们正在回归理智。现在，美国仅有的几个扎实的 Web3 项目都来自新加坡。确实，这些年轻人让我们感到骄傲。他们离开是有充分理由的，而他们肯定会回来的，因为这个地方对于那些成功的人来说是不错的地方。
```

服务返回 `preferredMaxLines=17`，不再把长气泡限制为 6 行。实际 Android 排版使用同等长度的 221 字响应验证：

```text
outcome=FULL
textSizePx=40.977577
lineSpacingMultiplier=1.0
renderedLineCount=12
segmentsOutsideRenderSlots=0
```

正常服务耗时 32556ms，长正文 1/1 成功；整页 14 组中 12 组成功或按保留策略完成。`Feux Lee E instagram:` 与 `DannV` 两个短 OCR 项未产生可靠翻译，保持原文，不影响长正文验收。

## Qwen 故障注入

同一聊天图改指向不可达端口 `http://192.168.0.63:18090`，请求在 289ms 内失败。结果为：

```text
longBodyCount=1
succeededLongBodyCount=0
safelyPreservedLongBodyCount=1
memberRegionCount=17
provider=none
failureCode=SELF_HOSTED_REQUEST_FAILED
translatedText==sourceText
```

因此 Qwen 故障没有触发 17 个 OCR 分片的端侧翻译，也没有生成低质量中文补丁。短标签仍可按各自策略处理，与长正文安全门控相互独立。

## 验收结论

| 验收项 | 结果 |
|---|---|
| 新闻绕图正文 10/10 行进入同一组 | 通过 |
| 请求与响应三个 `renderSlots` 一致 | 通过 |
| 113 字新闻译文依次回流三个槽 | 通过，FULL，2+2+2 行 |
| 17 行聊天正文作为单组翻译 | 通过 |
| 聊天译文在原气泡完整绘制 | 通过，FULL，12 行 |
| 超容量长文本启用“更多”，短项禁用 | 通过专项真机测试 |
| Qwen 失败时长正文整组保留 | 通过故障注入 |
| 模型协议残片进入回贴 | 未出现，服务端已清洗/拒绝 |
| Android 单元测试 | 177 项通过 |
| Rust 单元与 API 测试 | 15 项通过 |

本批 P0 可以判定通过。四项改造适合合并实施，因为它们共享同一份语义组和几何契约；但必须继续保留独立指标，避免“翻译成功”掩盖槽位越界，或“几何安全”掩盖长正文错误降级。

仍有三个后续项：本地 9B 服务的 32-41 秒延迟远高于手机端目标；`AFRICA-CANADA` 等标题前缀仍可增加完整翻译质量门；任意旋转文字需要从当前轴对齐槽扩展到真正多边形，本批只验证了手机截图常见的水平文本。

## 证据文件

- `news-report-final.json`：新闻页完整 OCR、过滤、语义组、逐区域位置、请求原文、响应和布局提示。
- `chat-report-final.json`：聊天页正常服务完整结果。
- `chat-qwen-outage-report.json`：不可达服务故障注入结果。
- `news-shape-layout.json`：新闻真实译文三槽排版结果。
- `chat-shape-layout.json`：聊天长正文排版结果。
- `news-source.png`、`chat-source.png`：验证原图。
- `text-layout-visual-validation.md`：不依赖原图背景的 OCR/译文纯文字坐标对照。
- `admin-news-image-upload-report.json`、`admin-chat-image-upload-report.json`：真机全屏采集上传请求的 OCR、语义组和翻译结果。
- `admin-history-desktop.png`：历史记录及状态过滤页面。
- `admin-news-detail-desktop.png`、`admin-chat-detail-mobile.png`：宽屏三列和手机纵向布局还原页面。

## 调试全屏图片、历史页与取消链路验证

调试图片上传增加了四重门控：Debug 构建、`LIVE_SCREEN` 场景、自建服务、存在本次待翻译组。图库、拍照和普通静态图片不上传；差分 OCR 即使只识别变化区域，也上传当前完整帧。图片先按最长边 1080px、JPEG 质量 72 压缩，服务端验证格式和魔数后独立落盘，不进入 OCR 或 Qwen 请求。请求审计 JSON 中不包含 `dataBase64`。

真机新闻页请求：

```text
audit_id=7611c71f-b262-439d-b3dc-0849d2b2d7b4
request_id=58b4e1c0-48a4-4277-8156-45ebbb489c8b
scene=LIVE_SCREEN status=SUCCEEDED
viewport=387x892 image=JPEG 387x892 68517 bytes
ocr_regions=22 semantic_groups=7 input_chars=727 duration_ms=43581
```

真机聊天页请求：

```text
audit_id=51c72c83-884d-4e08-a5bc-4589329dc2bb
request_id=1ad72f05-fb53-4c8a-afec-05eb2e5bd65f
scene=LIVE_SCREEN status=SUCCEEDED
viewport=1080x2400 image=JPEG 486x1080 93423 bytes
ocr_regions=30 semantic_groups=14 input_chars=673 duration_ms=24147
```

聊天图按比例压缩后上传，但 Web 端 OCR 和译文布局仍在 `1080x2400` viewport 中还原，不能把压缩图像素误当成 OCR 坐标。浏览器核验结果：1200x900 新闻详情三栏并排；390x844 聊天详情三栏纵向排列；两者均无页面级横向溢出，请求/响应 JSON 可展开查看。聊天结果包含 7 个 `COMPACT`、6 个 `MORE`、1 个 `FULL`，长内容没有因空间不足静默消失。

取消验证使用真机 Provider 发起长模型请求后主动取消：

```text
audit_id=01b85fc0-ba77-40c5-9dd9-435a278ebb0b
request_id=android-cancel-live-validation-2
scene=LIVE_SCREEN status=CANCELLED
groups=1 regions=2 input_chars=46 duration_ms=492
```

客户端先发送取消请求，再断开原翻译连接；服务端中止模型等待并写入 `CANCELLED`。历史页按取消状态过滤后仅显示该记录。悬浮窗状态覆盖“开始识别/识别中/识别成功/识别失败/识别已取消”，滚动触发的新一代识别不会被旧请求结果覆盖。

最终 APK：`app/build/outputs/apk/debug/app-debug.apk`，230973043 bytes，SHA-256 `ae0da75043fa69f16550124e144520dadeb730f2d607994f60b693c413a27e22`。
