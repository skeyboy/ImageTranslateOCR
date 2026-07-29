# Enhanced 羽化主题材料连续交互验证

日期：2026-07-29

设备：Xiaomi 23113RKC6C，Android 16，1220 x 2712

候选：Accessibility Enhanced `alpha=1` + 羽化局部主题材料 + 文本无独立底板

## 1. 结论

本阶段判定为 **PASS（渲染材料与交互链路）**。

- Light 30/30、Dark 30/30 滚动、触摸透传、单次隐藏、单次完成和原子呈现通过。
- 译文直接落在局部主题材料上，Light/Dark 代表帧未见普通 `alpha=0.72` 的灰色矩形贴片。
- 全页主题色仍拒绝；图表、彩色标记、浏览器栏和页面层级未被整页材料覆盖。
- 高斯仍不作为推荐默认值；50 视口矩阵已证明其会保留模糊原文轮廓。
- 普通模式继续保持 `alpha=0.72`、背景默认关闭；Enhanced 候选不在本轮自动启用。

本结论只覆盖 A3/A4/A7 材料子项/A8/A9/A11。当前 OCR 与翻译仍有失败区域和少量混排，A5/A6 不因本轮通过。

## 2. 目标：最终要证明或交付什么

证明在可信 Accessibility 悬浮层中，可使用 `alpha=1` 羽化局部主题材料替代文本自身的硬矩形背景，并在真实连续滚动中保持：

1. 页面一滚动，旧译文立即隐藏；稳定后只提交一组新译文。
2. 翻译层不阻断底层网页点击或滚动。
3. 原文/译文切换不改变页面锚点。
4. 停止后控制层与翻译层都被清理。
5. Light/Dark 中非文本内容和页面层级不被整页背景破坏。

## 3. 真实场景：用户实际如何使用

用户在英文技术长文中开启持续翻译，连续向下阅读。页面包含标题、段落、代码、图表、提示块、固定网页控件和浏览器栏。每次滑动后用户还会点击网页右侧固定按钮，并在阅读中切换一次原文/译文，最后从悬浮控制条退出。

本轮各执行：

- Light：30 次滚动 + 30 次底层点击 + 15 秒静止 + 原文/译文双向切换 + 退出。
- Dark：30 次滚动 + 30 次底层点击 + 15 秒静止 + 原文/译文双向切换 + 退出。

## 4. 方案与缺陷修复

### 4.1 文本与材料分离

译文没有自己的硬矩形背景。处理器先生成局部主题材料，核心区域完全遮住原文，边缘使用 smoothstep 羽化，再单独绘制文字。Enhanced 使用可信 `TYPE_ACCESSIBILITY_OVERLAY` 和 `alpha=1`，因此不需要普通悬浮层的灰色补偿区间。

### 4.2 连续滚动主信号

验证中发现纯像素差分存在两个相反问题：

- 不屏蔽译文 patch 时，`alpha=1` 译文显隐会自触发“页面运动”。
- 持续屏蔽 patch 时，真实滚动若主要发生在文字区，剩余差分可能不足而漏掉滚动。

最终方案在 Enhanced 中使用 Accessibility `TYPE_VIEW_SCROLLED` 作为滚动主信号，700 ms 事件去抖后执行稳定截图；像素检测保留为非滚动变化的稳定后兜底，但不再与 Accessibility 抢占“立即隐藏”。翻译 patch 仍加入签名忽略区，避免材料显隐自激。

### 4.3 自动化隔离

UIAutomator 会暂时解绑正在验证的辅助功能服务，因此它只用于启用 Enhanced 之前的浏览器和录屏授权。启用后，网页通过本机 HTTP 探针上报滚动位置和点击计数，避免测试框架改变被测服务生命周期。

## 5. 验收：逐项列出可操作标准

| 项目 | 标准 | Light | Dark | 判定 |
|---|---:|---:|---:|---|
| 滚动有效 | 位移 >= 200 px | 30/30 | 30/30 | PASS |
| 触摸透传 | 每轮点击计数 +1 | 30/30 | 30/30 | PASS |
| 单次完成 | 每轮恰好 1 次 completion | 30/30 | 30/30 | PASS |
| 单次隐藏 | 每轮恰好 1 次 hidden | 30/30 | 30/30 | PASS |
| 原子呈现 | 单组且 generation 有序 | 30/30 | 30/30 | PASS |
| 运动到隐藏 P90 | <= 150 ms | 18 ms | 18 ms | PASS |
| 末次运动到提交 P90 | <= 2000 ms | 1520 ms | 1546 ms | PASS |
| 静止自激 | 15 秒新增 OCR/隐藏 = 0 | 0 | 0 | PASS |
| 非文本保护 | 标记 SSIM >= 0.97，浏览器栏 >= 0.99 | 1.0 / 1.0 | 1.0 / 1.0 | PASS |
| 原文切换 | 隐藏/恢复各 1 次，锚点漂移 <= 16 px | 1/1，0 px | 1/1，0 px | PASS |
| 退出清理 | 悬浮窗口从 >= 2 降为 0 | 2 -> 0 | 2 -> 0 | PASS |

## 6. OCR 与翻译可信度边界

本轮使用当前英文 OCR 和翻译链路，不使用黄金译文注入：

- Light 30 轮累计 `failed_regions_total=28`，保留拉丁字符 P90 为 3.77%。
- Dark 30 轮累计 `failed_regions_total=21`，保留拉丁字符 P90 为 5.13%。
- 代表帧仍可见标题误识别、短词混排和段落措辞不自然。

因此，高准确度 OCR + 翻译预计会明显改善混排、漏译和异常长度，但不会改变本轮已经证明的材料结论：普通 `alpha=0.72` 仍有贴片感；Enhanced `alpha=1` 羽化主题材料可以去掉独立文本底板。

## 7. 范围：本周做什么、不做什么

本轮完成：羽化主题材料、Enhanced 生命周期恢复、patch 签名屏蔽、Accessibility 滚动主信号、HTTP 页面探针、Light/Dark 各 30 轮、原文切换与退出清理。

本轮不做：提高 OCR/翻译模型准确率、全页主题色、全页高斯、普通模式改为不透明、横屏、第二台 OEM 兼容性、动态非滚动内容专项。

## 8. 时间：阶段节点、截止时间和停止条件

- 2026-07-29：50 视口材料矩阵完成。
- 2026-07-29：真实 Enhanced Light/Dark 各 30 轮完成。
- 停止条件：任一主题出现触摸阻断、重复隐藏/完成、P90 超门限、静止自激或退出残留，则不进入候选；最终两组均未触发。

## 9. 依赖：需要谁提供什么

- Android：用户授予屏幕录制与辅助功能权限。
- 产品：确认 Enhanced 的权限说明、默认关闭和退出入口。
- OCR/翻译负责人：后续提供高准确度黄金集，不与本轮材料门混为一项。
- QA：补第二 OEM、横屏、系统字体放大和辅助功能服务被系统回收场景。

## 10. 风险：未知点及最晚升级时间

| 风险 | 影响 | 最晚升级条件 |
|---|---|---|
| 单一 Xiaomi 设备 | OEM 行为不可外推 | 合入默认候选前补至少 Pixel/三星之一 |
| 非滚动动态内容仍依赖像素兜底 | 视频、轮播或局部刷新可能延迟 | 下一阶段动态内容 20 场景出现漏更即升级 |
| Accessibility 服务被系统回收 | Enhanced 可能回退普通模式 | 第二设备生命周期测试出现一次未恢复即升级 |
| 当前 OCR/翻译失败区域较多 | 渲染自然但语义仍不可靠 | A5/A6 黄金集前不得宣称整体体验通过 |

## 11. 证据

- Light 汇总：[summary.json](run/light/summary.json)
- Dark 汇总：[summary.json](run/dark/summary.json)
- Light 逐轮：[scroll-results.jsonl](run/light/scroll-results.jsonl)
- Dark 逐轮：[scroll-results.jsonl](run/dark/scroll-results.jsonl)
- Light 代表帧：[第 1 轮](run/light/checkpoints/scroll-01.png)、[第 15 轮](run/light/checkpoints/scroll-15.png)、[第 30 轮](run/light/checkpoints/scroll-30.png)
- Dark 代表帧：[第 1 轮](run/dark/checkpoints/scroll-01.png)、[第 15 轮](run/dark/checkpoints/scroll-15.png)、[第 30 轮](run/dark/checkpoints/scroll-30.png)
- Dark 原文切换：[译文](run/dark/translated-visible.png)、[原文](run/dark/source-visible.png)
- 自动化脚本：[live-continuous-scroll-validation.sh](../../../scripts/live-continuous-scroll-validation.sh)

录屏保留在 `run/light` 与 `run/dark`，但由本目录 `.gitignore` 排除，不纳入 Git。

## 12. 下一阶段

1. 在第二台 OEM 设备执行相同 Light/Dark 30 轮，重点检查 Accessibility 服务回收和窗口类型切换。
2. 新增 20 个非滚动动态内容场景，验证像素兜底在局部刷新、折叠展开、视频和轮播中的更新完整性。
3. 使用高准确度 OCR + 翻译黄金集复跑 A5/A6，只评估语义和排版，不回退已通过的材料方案。
