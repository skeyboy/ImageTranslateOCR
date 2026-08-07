# V002：当前页 OCR 与 Pnuts 网络返回人工核验

> 验证日期：2026-08-05  
> 设备：`23113RKC6C`，1440x3200  
> OCR：`ML_KIT` + `ENGLISH` + fast OCR  
> Pnuts：`https://api-dev.pnutsai.com/api/v1/translate/regions`

## 结论

1. OCR 用时 557 ms，返回 21 行、19 个语义组。正文大部分可识别，但有 `Xi's Time -> N'sTime`、`room -> roomn` 等错误，并将状态栏和新华网 logo 识别为文本。
2. 当前语义分组仍有欠合并：第一条两行标题被拆成 2 组，因为同 block 的两行高度比约为 0.649，刚好低于当前 0.65 阈值；第二条三行标题被拆成 2 组，因为最后一行被 ML Kit 分到不同 block，而当前反误合并规则禁止跨 block 合并。
3. 使用当前客户端批量格式请求 12 个可翻译组时，Pnuts HTTP 200，但 12/12 返回 `FAILED`，耗时 8,276 ms，响应没有错误码或原因。
4. 保持客户端的全局 `auto/auto` 语种字段，改为每次只发送 1 个语义组后，9/12 返回 `TRANSLATED`、3/12 `FAILED`。这说明当前主要阻塞不只是 OCR 文本质量，还包括 Pnuts v1 批量/`useContext` 调用语义。

![本次未回贴的原始截图](./source-original.png)

## OCR 逐行结果

| 行 | OCR 文本 | 置信度 | 坐标 `(left,top)-(right,bottom)` | 人工注记 |
|---:|---|---:|---|---|
| 1 | `VPNX16` | 0.633 | `(1009,43)-(1300,86)` | 状态栏图标误识别 |
| 2 | `16:32` | 0.913 | `(97,44)-(220,87)` | 正确，系统时间 |
| 3 | `Xi's Time` | 0.775 | `(218,206)-(442,258)` | 正确，浏览器标题 |
| 4 | `N'sTime` | 0.587 | `(692,419)-(1401,617)` | 错，图片内应为 `Xi's Time` |
| 5 | `www.news.cn` | 0.740 | `(1238,676)-(1365,686)` | 正确，logo 文本 |
| 6 | `EWS` | 0.750 | `(1280,737)-(1333,767)` | 错，`NEWS` 的局部漏字 |
| 7 | `net corm` | 0.507 | `(1322,760)-(1364,781)` | 错，logo 噪声 |
| 8 | `Home)(Xi Story Xiplomacy Xi Focus` | 0.772 | `(83,875)-(1329,950)` | 导航整行误合并，夹杂括号噪声 |
| 9 | `Xi holds talks with Slovak president in Beijing` | 0.879 | `(35,1722)-(1102,1772)` | 正确 |
| 10 | `Xi Story:Six-foot alley reveals Chinese wisdom` | 0.880 | `(68,1900)-(1391,1977)` | 基本正确，冒号后缺空格 |
| 11 | `of making roomn for others` | 0.888 | `(71,2002)-(818,2052)` | `roomn` 应为 `room` |
| 12 | `2026-08-04` | 0.916 | `(37,2077)-(248,2107)` | 正确，本地保留 |
| 13 | `PLA celebrates 99th founding anniversary in` | 0.912 | `(72,2203)-(1327,2266)` | 正确 |
| 14 | `march toward world-class military,with peace` | 0.894 | `(70,2283)-(1385,2352)` | 文本正确，逗号后缺空格 |
| 15 | `as enduring mission` | 0.909 | `(73,2377)-(638,2434)` | 正确，但被分到新 block |
| 16 | `2026-08-01` | 0.928 | `(37,2451)-(244,2481)` | 正确，本地保留 |
| 17 | `Xi signs order to commend outstanding` | 0.911 | `(71,2583)-(1184,2637)` | 正确 |
| 18 | `military individual` | 0.879 | `(70,2658)-(607,2724)` | 正确 |
| 19 | `2026-07-31` | 0.926 | `(37,2741)-(245,2771)` | 正确，本地保留 |
| 20 | `Xi's Archive` | 0.841 | `(432,2869)-(1005,2943)` | 正确 |
| 21 | `Ai` | 0.689 | `(674,3017)-(755,3091)` | 浏览器底部 UI |

完整 block ID、line index、坐标和语义组证据见 [`ocr.json`](./ocr.json)。

## 语义分组核验

19 个组中只有两个多行组：

- `PLA celebrates 99th founding anniversary in` + `march toward world-class military,with peace`；
- `Xi signs order to commend outstanding` + `military individual`。

建议人工核验的两个欠合并：

| 页面完整标题 | 当前分组 | 期望 |
|---|---:|---:|
| `Xi Story: Six-foot alley reveals Chinese wisdom / of making room for others` | 2 组 | 1 组 |
| `PLA celebrates ... / march ... / as enduring mission` | 2 组 | 1 组 |

第一条是同 block 内的字高阈值问题，第二条是跨 block 的强几何连续证据未被采用，两者需要分别修正。

## Pnuts 客户端批量返回

请求与当前 `RemoteTranslationProvider` 一致：12 个可翻译组放在同一请求，全局语种为 `auto/auto`，region 为 `en/zh`，`useContext=true`。日期由客户端保留，未发送。

| 指标 | 结果 |
|---|---:|
| HTTP | 200 |
| 耗时 | 8,276 ms |
| `TRANSLATED` | 0 |
| `FAILED` | 12 |
| 服务端错误码/原因 | 未返回 |

原始请求见 [`pnuts.request.json`](./pnuts.request.json)，原始响应见 [`pnuts.response.json`](./pnuts.response.json)。

## Pnuts 单语义组严格对照

下表保持全局 `auto/auto` 和 region `en/zh`，只改为每次 1 个语义组、`useContext=false`；总网络时间 48,588 ms。

| OCR/语义组原文 | 状态 | Pnuts 返回 |
|---|---|---|
| `N'sTime` | `TRANSLATED` | `N'sTime` |
| `www.news.cn` | `TRANSLATED` | `www.news.cn` |
| `EWS` | `TRANSLATED` | `EWS` |
| `net corm` | `FAILED` | - |
| `Home)(Xi Story Xiplomacy Xi Focus` | `TRANSLATED` | `首页)(习近平的故事 习式外交 习近平聚焦` |
| `Xi holds talks with Slovak president in Beijing` | `TRANSLATED` | `习近平在北京同斯洛伐克总统举行会谈` |
| `Xi Story:Six-foot alley reveals Chinese wisdom` | `TRANSLATED` | `习语故事：六尺巷展现中国智慧` |
| `of making roomn for others` | `FAILED` | - |
| `PLA celebrates 99th founding anniversary in march toward world-class military,with peace` | `FAILED` | - |
| `as enduring mission` | `TRANSLATED` | `作为持久的使命` |
| `Xi signs order to commend outstanding military individual` | `TRANSLATED` | `习近平签署命令表彰优秀军事个人` |
| `Xi's Archive` | `TRANSLATED` | `习近平文库` |

这里的 `TRANSLATED` 是服务端状态；`N'sTime`、`www.news.cn`、`EWS` 实际返回了原文。完整对照见 [`pnuts-single-app-fields.joined.json`](./pnuts-single-app-fields.joined.json)。

辅助对照中，将全局语种显式设为 `en/zh` 后为 10/12 成功，说明全局语种字段也可能影响质量门控；该对照见 [`pnuts-single.joined.json`](./pnuts-single.joined.json)。

## 人工核验建议

- 优先确认两个多行标题是否应各自合并为 1 个组。
- 确认导航栏是否应作为一个整体翻译；当前 OCR 已将多个按钮合成一行，会破坏独立回贴。
- Pnuts v1 批量请求在没有错误详情的情况下全部失败，需服务端确认 `useContext=true` 是否真正支持多 region。
- 端侧短期可用“每语义组单独请求”规避批量失败，但本次 12 组串行已达 48.6 s，必须配合有界并发、超时和缓存，不能直接作为实时实现。

## 证据文件

| 文件 | 内容 |
|---|---|
| [`source-original.png`](./source-original.png) | 未翻译回贴的 1440x3200 输入截图 |
| [`source-with-existing-overlay.png`](./source-with-existing-overlay.png) | 移除覆盖层前的对照截图 |
| [`ocr.json`](./ocr.json) | 全部 OCR 行、坐标、block 与语义组 |
| [`pnuts.request.json`](./pnuts.request.json) | 当前客户端批量格式 |
| [`pnuts.response.json`](./pnuts.response.json) | Pnuts 批量原始响应 |
| [`pnuts-single-app-fields.joined.json`](./pnuts-single-app-fields.joined.json) | 严格单组对照 |
| [`summary.json`](./summary.json) | 机读结论与人工核验候选项 |
