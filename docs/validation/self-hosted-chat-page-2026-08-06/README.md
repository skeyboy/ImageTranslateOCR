# 非文章聊天页：区域关联分组与自建 9B 翻译验证

## 验证对象与环境

- 原图：`source.jpg`，1080 x 2400，SHA-256 `1d2aee8a6cd774dc9af336a944886a759cbfd4770a6197dbcbdee755345e0afc`
- Android 真机：`23113RKC6C`，局域网 ADB `192.168.0.46:42087`
- OCR：端侧 ML Kit，`AUTO`
- 翻译服务：Axum v2，`http://192.168.0.63:8090/api/v2/translate/groups`
- 模型：本地 Ollama `qwen3.5:9b`
- 验证日期：2026-08-06

![测试原图](source.jpg)

## 本轮分组改造

旧逻辑只逐对比较上一行和下一行的上下间距、水平重叠和对齐关系。本图中 `- Prof` 与下一行正文左边界接近、间距只有 26px，因此被错误并入 17 行正文，形成 18 行组。

本轮新增候选组整体的 `REGION_OCCUPANCY` 判定：

1. 检查同一水平带是否存在独立伴随字段，例如发送者右侧的手机号。
2. 检查短标签到正文主列的占区扩张，短发送者标签不能仅因左对齐就并入宽正文。
3. 检查宽标题到窄元数据的占区收缩，截断群标题不能与在线人数合并。
4. 多行组继续以组内行宽中位数判断主文字列，防止后续紧邻元数据被吸收。
5. 间距、重叠、OCR block、标点和角色仍保留，但只作为组合证据，不再单独决定合并。

新增专项单测覆盖“发送者 + 同行手机号 + 多行消息”和“截断标题 + 窄元数据”，完整 `:app:testDebugUnitTest`、Debug APK 和 AndroidTest APK 构建均通过。

## OCR 原始识别结果

OCR 用时 1,811ms，共得到 34 条。语义请求最终为 33 个 region；第 1 条 `4G` 与第 2 条状态栏区域高度重叠，在去重阶段被移除。

| # | OCR 文字 | 原图坐标 | 模型置信度 |
|---:|:---|:---|---:|
| 0 | `17:27 \| 0.9K/s` | `(171,45)-(417,84)` | 0.78 |
| 1 | `4G` | `(872,47)-(898,62)` | 0.86 |
| 2 | `HD ll HD 99` | `(713,51)-(1011,79)` | 0.58 |
| 3 | `Alfor Humanity Com...` | `(294,133)-(830,184)` | 0.78 |
| 4 | `22AE` | `(289,194)-(386,229)` | 0.66 |
| 5 | `Feux Lee on instagram:` | `(160,251)-(596,286)` | 0.75 |
| 6 | `"Ilived in Singapore for` | `(167,301)-(609,341)` | 0.83 |
| 7 | `instagram.com` | `(214,387)-(484,425)` | 0.84 |
| 8 | `22:08` | `(607,470)-(691,497)` | 0.93 |
| 9 | `Instagram` | `(276,560)-(477,602)` | 0.80 |
| 10 | `- Prof` | `(154,746)-(259,781)` | 0.79 |
| 11 | `+65 9684 3435` | `(656,751)-(895,776)` | 0.93 |
| 12 | `The Web3 industry tried and we did` | `(153,807)-(887,848)` | 0.89 |
| 13 | `quite well.Until the country decided` | `(154,865)-(900,908)` | 0.89 |
| 14 | `to invest in something that did not` | `(153,923)-(867,964)` | 0.90 |
| 15 | `fit into the country nor the Web3` | `(154,980)-(843,1022)` | 0.90 |
| 16 | `community.Then all hell broke` | `(154,1033)-(793,1081)` | 0.85 |
| 17 | `loose.The rest is history.After that,` | `(155,1096)-(891,1139)` | 0.87 |
| 18 | `we were suddenly All in AI,that was` | `(153,1155)-(905,1196)` | 0.85 |
| 19 | `really dangerous and riskier than` | `(155,1213)-(822,1256)` | 0.91 |
| 20 | `Web3.Glad that recently we are` | `(153,1271)-(821,1313)` | 0.89 |
| 21 | `Coming back to our senses.Now,` | `(154,1331)-(833,1369)` | 0.83 |
| 22 | `the few solid Web3 projects in the` | `(153,1386)-(863,1430)` | 0.89 |
| 23 | `US are from Singapore.Indeed,` | `(155,1444)-(802,1486)` | 0.87 |
| 24 | `these youngsters have made us` | `(153,1503)-(804,1545)` | 0.90 |
| 25 | `proud.They left for a good reason,` | `(155,1558)-(894,1603)` | 0.90 |
| 26 | `and they will come back for sure` | `(166,1618)-(828,1659)` | 0.88 |
| 27 | `because this place is good for those` | `(155,1676)-(900,1717)` | 0.89 |
| 28 | `who have made it.` | `(153,1735)-(529,1769)` | 0.85 |
| 29 | `22:43` | `(825,1762)-(911,1786)` | 0.91 |
| 30 | `Lim CW` | `(185,1952)-(324,1982)` | 0.70 |
| 31 | `+65 9478 2794` | `(443,1955)-(678,1980)` | 0.94 |
| 32 | `DainV` | `(109,2109)-(219,2137)` | 0.51 |
| 33 | `86 802` | `(441,2109)-(535,2138)` | 0.64 |

人工核验发现 OCR 的主要问题是：`AI` 被识别为 `Al`，`22人在线` 被识别为 `22AE`，链接预览人名 `Felix` 被识别为 `Feux`，底部遮挡区存在 `DainV`、`86 802` 等残缺文字。正文 17 行内容和坐标总体完整。

## 分组前后对照

| 项目 | 改造前 | 改造后 | 判定 |
|:---|:---|:---|:---:|
| 总组数 | 14 | 16 | 两处错误合并被拆开 |
| 群标题/在线人数 | 2 行合成 1 组 | 两个 1 行组 | 通过 |
| 发送者/正文 | `- Prof` + 17 行正文，共 18 行 | `- Prof`、手机号、17 行正文各自独立 | 通过 |
| 正文组坐标 | `(153,746)-(905,1769)` | `(153,807)-(905,1769)` | 发送者区域不再污染正文锚点 |
| 正文分组证据 | 仅几何、标点 | 增加 `REGION_OCCUPANCY` | 可审计 |

正文请求组为 `semantic-4cdd063f`，包含 17 个成员 region；发送者为 `semantic-11fb40b8`，手机号为 `semantic-25000fcd`。三者之间没有成员交叉。

## 请求与输出逐组对照

完整请求是 `request-region-occupancy.json`：16 groups、33 regions、720 个输入字符，保留 1080 x 2400 视口、每行坐标、阅读顺序、组成员和分组证据。

服务原始响应是 `response-region-occupancy.json`：HTTP 200，14 个 `TRANSLATED`、2 个时间戳 `PRESERVED`、0 个服务失败，总耗时 59,605ms。Android 真机实际链路耗时 43,505ms；Android 对“服务原样返回英文”的组执行结果门控和本地降级，因此最终为 9 成功、7 失败。

| 顺序 | 角色 | 成员 | 请求原文 | 服务原始输出 | Android |
|---:|:---|---:|:---|:---|:---|
| 0 | BODY | 1 | `17:27 \| 0.9K/s` | 原样 | 成功/保留 |
| 1 | BODY | 1 | `HD ll HD 99` | 原样 | 失败，状态栏噪声 |
| 2 | BODY | 1 | `Alfor Humanity Com...` | 原样 | 失败，OCR 错字且截断 |
| 3 | BODY | 1 | `22AE` | 原样 | 失败，OCR 乱码 |
| 4 | BODY | 2 | `Feux Lee on instagram:` / `"Ilived in Singapore for` | `Feux Lee 在 instagram:` / `"我曾在新加坡居住` | 成功/回贴 |
| 5 | BODY | 1 | `instagram.com` | 原样 | 失败，标识符 |
| 6 | TIMESTAMP | 1 | `22:08` | `PRESERVED` | 成功/保留 |
| 7 | BODY | 1 | `Instagram` | 原样 | 失败，品牌名 |
| 8 | LIST_ITEM | 1 | `- Prof` | 原样 | 远端原样被拒绝，本地降级为 `- 教授` 并回贴 |
| 9 | BODY | 1 | `+65 9684 3435` | 原样 | 成功/保留 |
| 10 | BODY | 17 | 完整 Web3 消息 | 完整中文译文 | 翻译成功，排版安全门控保留原文 |
| 11 | TIMESTAMP | 1 | `22:43` | `PRESERVED` | 成功/保留 |
| 12 | BODY | 1 | `Lim CW` | 原样 | 失败，姓名/昵称 |
| 13 | BODY | 1 | `+65 9478 2794` | 原样 | 成功/保留 |
| 14 | BODY | 1 | `DainV` | 原样 | 失败，遮挡区 OCR 残片 |
| 15 | BODY | 1 | `86 802` | 原样 | 成功/保留 |

正文原文：

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
proud.They left for a good reason,
and they will come back for sure
because this place is good for those
who have made it.
```

服务原始译文：

```text
Web3行业曾尝试过，我们也做得相当不错。直到该国决定投资一些既不符合国家利益也不符合 Web3 社区的事物时，一切才彻底失控。剩下的都是历史了。在那之后，我们突然全部转向 AI，那真的非常危险且风险比 Web3 更大。很高兴最近我们正在重新回归理智。现在，美国仅有的几个扎实的 Web3 项目都来自新加坡。确实，这些年轻人让我们感到骄傲。他们离开是有正当理由的，而他们肯定会回来的，因为这个地方对于那些成功的人来说是好的。
```

人工判断：译文语义完整、前后连贯，证明“17 行正文整组上传 -> 服务整页理解 -> 组级译文返回”成立。末句“对于那些成功的人来说是好的”略生硬，可优化为“这里适合那些真正做成事情的人”，但不影响本轮分组验证。

## 最终回贴结果

正式 UI 对应服务审计：

```text
request_id=f0d7c3b3-6ee7-492e-a7f4-ef16c0b682e5
scene=STATIC_IMAGE
group_count=16
region_count=33
input_chars=720
status=SUCCEEDED
model=qwen3.5:9b
duration_ms=43893
```

Android 正式 UI 显示：`完成，共替换 2 段文字；7 段翻译失败；1 段因擦除或排版风险保留原文`。最终保存图 SHA-256 为 `dff7f388a4b978642b04082e79e5870493e132528fdf45f12bc581b64ca7ad4a`。

![最终回贴图](translated-region-occupancy-output.jpg)

17 行正文没有被回贴，不是分组或翻译失败。服务仍对长组返回 `preferredMaxLines=6`；端侧无法在原气泡区域内用 6 行容纳完整中文，因而按“不裁切、不覆盖”的安全策略恢复原文。该行为避免了破坏图片，但说明服务布局提示仍按文章短段落假设生成，不适配长聊天气泡。

`- Prof` 被本地降级翻译成 `- 教授` 也不理想。它在本图中更可能是昵称/发送者标签，后续应基于“同行手机号 + 气泡头部 + 短文本”的区域角色推断为 `SENDER` 或 `IDENTIFIER`，默认保留，而不是当作列表项翻译。

## 结论与推荐顺序

**区域关联分组改造通过，非文章页端到端仍为部分通过。** 本轮已经证明不能只机械使用上下/水平间距：加入同行伴随字段、占区宽度突变和组内主列后，发送者、手机号、正文及标题元数据的边界均恢复正确，17 行正文也得到稳定完整译文。

后续推荐顺序：

1. P0：布局提示改为至少保留源组行数预算，或返回基于实际译文和目标矩形测量后的行数；不能把 17 行源组固定压为 6 行。
2. P0：为聊天气泡增加 `SENDER`/`METADATA` 角色，同行手机号作为强证据；昵称和发送者默认不翻译。
3. P0：非文章页启用轻量 UI/状态栏/遮挡区过滤，不能继续把 34 条 OCR 全量送翻译。
4. P1：将“服务返回原文”明确建模为 `PRESERVED`，避免 Android 把合理保留计为翻译失败并触发无意义本地降级。
5. P1：在结果一致后优化 43-60 秒时延。

## 证据文件

- `report-region-occupancy.json`：真机 OCR、过滤、语义组、Android 翻译结果
- `request-region-occupancy.json`：完整 v2 请求
- `response-region-occupancy.json`：完整服务原始响应
- `response-region-occupancy.http.txt`：HTTP 200 响应头
- `comparison-region-occupancy.json`：请求、服务、Android 逐组合并对照
- `translated-region-occupancy-output.jpg`：真机最终保存图
- `ui-region-occupancy-translated.png`：正式 UI 结果视图

