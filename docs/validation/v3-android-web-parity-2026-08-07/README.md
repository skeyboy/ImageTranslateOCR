# V3 Android 与 Web 回贴一致性验证

日期：2026-08-07  
服务端记录：<http://192.168.0.63:8090/admin/requests/df0f084c-85cb-4b0a-9767-be04271ec2a0>  
请求 ID：`cd0cc6f3-a02a-4b8d-a5db-2cbc7caabc18`

## 1. 现象对照

Android 回贴结果：

![Android 修复前回贴](./android-before.png)

Web 按服务端计划还原结果：

![Web 服务端计划还原](./web-reference.png)

两端都收到了同一次 V3 请求的结果。第一组和第三组在 Android 已被翻译，只有中间的双槽长正文仍显示英文；Web 的三个服务端组均为 `FULL`。这排除了网络未返回、Qwen 翻译失败和整批 V3 响应未被端侧接收三种可能。

## 2. 请求与服务端输出

请求摘要：

```json
{
  "schemaVersion": 3,
  "scene": "LIVE_SCREEN",
  "viewport": { "width": 1440, "height": 3200 },
  "clientGroupCount": 5,
  "regionCount": 22,
  "inputChars": 830,
  "debugCapture": null
}
```

服务端摘要：

```json
{
  "status": "SUCCEEDED",
  "provider": "self-hosted-qwen-layout-plan-v3",
  "modelVersion": "qwen3.5:9b",
  "planVersion": "server-semantic-plan-v2",
  "mode": "AUTHORITATIVE",
  "clientGroupCount": 5,
  "plannedGroupCount": 3,
  "mergedGroupCount": 2,
  "translatedGroupCount": 3,
  "failedGroupCount": 0,
  "totalMs": 33547
}
```

Android 未回贴的中间组原始 OCR 行与位置：

| 行 | OCR 原文 | bounds |
| ---: | --- | --- |
| 1 | Meanwhile,the Department of | `(64,1206)-(1008,1278)` |
| 2 | Meteorology and Hydrology under the Lao | `(34,1323)-(1384,1391)` |
| 3 | Ministry of Agriculture and Environment | `(64,1424)-(1314,1500)` |
| 4 | issued warning on Wednesday that | `(33,1546)-(1210,1608)` |
| 5 | widespread thunderstorms,moderate to | `(29,1658)-(1317,1723)` |
| 6 | heavy rainfall,and occasional strong winds | `(53,1762)-(1398,1833)` |
| 7 | are expected to continue in sonme areas. | `(31,1883)-(1301,1944)` |
| 8 | The department identified 30districts | `(31,1983)-(1246,2059)` |
| 9 | across 10 provinces as being at high risk of | `(32,2106)-(1402,2173)` |
| 10 | flash floods and landslides. | `(30,2218)-(889,2272)` |

该组的服务端输出：

```json
{
  "groupId": "server-semantic-3f3765e8--semantic-9db5ead0",
  "sourceGroupIds": ["semantic-3f3765e8", "semantic-9db5ead0"],
  "groupingConfidence": 0.96,
  "status": "TRANSLATED",
  "translatedText": "与此同时，老挝农业与环境部下属的气象与水文局于周三发出警告称，部分地区预计将持续出现大范围雷暴、中到大雨以及偶尔的强风。该局已确定全国10个省共30个地区面临山洪和泥石流的高风险。",
  "layoutHint": {
    "preferredMaxLines": 10,
    "minimumTextScale": 0.86,
    "maximumTextScale": 1.0,
    "lineSpacingMultiplier": 0.92,
    "overflowStrategy": "REFLOW_THEN_SCALE_THEN_MORE",
    "allowMore": true,
    "sourceLineCount": 10,
    "layoutShape": "FLOW_SLOTS",
    "renderSlots": [
      { "left": 64, "top": 1206, "right": 1008, "bottom": 1278 },
      { "left": 29, "top": 1323, "right": 1402, "bottom": 2272 }
    ]
  }
}
```

## 3. 根因

端侧没有丢失服务端数据。`TranslateManager` 已接受该 `TRANSLATED` 结果，处理器也已保留原始 OCR 行框、服务端 `renderSlots` 和布局提示。失败发生在最终 patch 生成阶段：

1. Android 实时回贴器独立计算字号和最大行数，没有完整复用静态图片布局策略；
2. 形状排版器逐槽填充时，只要任意一个槽无法在当前字号下容纳一个前缀，就立即判定整组失败；
3. 中间组的首槽只有一行高，是最容易触发该条件的槽；
4. patch 为空后，端侧按“不裁切、不覆盖”策略恢复该组原图，所以视觉上表现为中段英文，且此前异常被静默捕获。

Web 使用浏览器文本流分别填充两个槽，不具有 Android 的“单槽失败即整组失败”条件，因此同一服务端计划能够完整显示。这就是两端差异的直接原因。

## 4. 修复

本次保持职责边界不变：服务端返回语义组、完整译文、布局提示和几何槽；Android 使用本机字体度量完成最终绘制。没有让服务端预切中文行，因为服务端字体度量不能保证与设备一致。

Android 调整如下：

- 实时回贴与静态图片回贴统一使用 `StaticImageTextLayoutPolicy`；
- `sourceLineCount` 参与最大行数计算，且最小字号永远不会高于首选字号；
- 单个槽无法容纳文字时跳过该槽，继续尝试剩余服务端槽，不再直接丢弃整组；
- 服务端允许 `allowMore` 时增加一次有边界的压缩重试，仍不允许越过 `renderSlots`；
- 缓存继续包含槽位签名，服务端形状变化时不会复用旧 patch；
- patch 发生异常时记录组 ID、槽位数和源行数，避免后续静默恢复原文。

## 5. 验证结果

| 验证项 | 结果 |
| --- | --- |
| V3 响应与布局合约测试 | 通过 |
| 静态字号/行数策略测试 | 通过 |
| 多槽缓存签名测试 | 通过 |
| 双槽长正文 Android 回归用例编译 | 通过 |
| Debug APK 构建 | 通过，220 MB |
| 真机主 APK 安装与启动 | 通过，设备 `23113RKC6C` |
| 真机测试 APK 安装 | 手机安全策略取消，`INSTALL_FAILED_USER_RESTRICTED` |
| 服务端记录页 | HTTP 200，可人工继续对照 |

新增的真机回归用例直接使用本记录的 1440×3200 画布、10 行 OCR bounds、两个服务端槽和完整译文，断言生成 1 个完整 overlay patch。测试 APK 已成功构建；由于手机拒绝安装测试包，本轮没有把该断言标记为真机已执行。

## 6. 结论

本样本无需重构服务端翻译流程，也无需把服务端结果降级成逐行译文。服务端语义重组和 Web 还原均正确，问题集中在 Android 最终形状排版的全有或全无判定及重复的字号策略。

修复后的实现直接消费服务端 V3 几何和提示，同时保留 Android 对字体、裁切和越界的最终控制。下一次人工采集同一页面时，重点确认中间 10 行组已出现中文；若设备字体仍触发特殊失败，可通过 `BackgroundImageProcessor` 日志直接获得具体组和槽位信息。
