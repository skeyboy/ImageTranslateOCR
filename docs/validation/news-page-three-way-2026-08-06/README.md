# 新闻页三路图片翻译对比验证（2026-08-06）

## 验证对象

- 原图：`source.png`，387 x 892。
- 对照 A：`translation-run-a.png`，自建 Qwen 服务成功返回后的持续翻译结果。
- 对照 B：`translation-run-b.png`，自建服务失败后降级到端侧分片翻译的结果。
- 当前正式图片翻译输出：`static-ui-saved-output.jpg`。

![原图](source.png)

![对照 A](translation-run-a.png)

![对照 B](translation-run-b.png)

![当前正式图片翻译输出](static-ui-saved-output.jpg)

## 验证方法

1. 将原图上传到真机 `23113RKC6C`，执行当前 `ImageTranslateActivity` 正式图片翻译流程。
2. 另执行设备专项测试，记录 OCR 全量结果、静态内容过滤、语义组、组级译文和布局提示。
3. 使用中文 OCR 再识别两个既有译文截图，避免只凭肉眼概括回贴内容。
4. 结合 Axum 审计确定两张译文截图实际走过的服务链路。

正式图片翻译调用自建服务：

```text
request_id=92835e01-8b6a-4001-be98-e1e87b6f7297
scene=STATIC_IMAGE
group_count=8
region_count=16
input_chars=553
model=qwen3.5:9b
status=SUCCEEDED
duration_ms=48188
```

专项记录调用：

```text
request_id=4ba80386-fe33-4735-a7bf-432bf5110963
scene=STATIC_IMAGE_VALIDATION
group_count=8
region_count=16
input_chars=553
model=qwen3.5:9b
status=SUCCEEDED
duration_ms=37867
```

专项测试在服务调用后因 8 组中有 1 组失败而触发严格断言，但报告已完整落盘，不影响本次 OCR、分组和译文分析。

## 原图 OCR 结果

OCR 耗时 1641ms，共识别 29 个文字区域。静态内容过滤保留 16 个、排除 13 个。

| # | OCR 原文 | bounds `(l,t,r,b)` | 置信度 | 当前处理 |
|---:|---|---|---:|---|
| 1 | `23113RKC6C` | `84,11,167,21` | 0.856 | 排除 |
| 2 | `13:35` | `23,43,55,54` | 0.920 | 排除 |
| 3 | `AFRICA-CANADA:Boost for` | `58,87,245,103` | 0.832 | 排除 |
| 4 | `University World News` | `6,132,208,159` | 0.877 | 排除 |
| 5 | `Global Edition Africa Edition` | `14,180,243,193` | 0.870 | 保留 |
| 6 | `Asia Hub` | `289,180,355,193` | 0.853 | 排除 |
| 7 | `AFRICA` | `8,332,46,340` | 0.841 | 保留 |
| 8 | `AFRICA-CANADA:Boost for Next` | `7,344,323,364` | 0.833 | 保留 |
| 9 | `Einstein centres` | `8,368,160,382` | 0.898 | 保留 |
| 10 | `Munyaradzi Makoni 18 July 2010` | `8,399,182,410` | 0.845 | 保留 |
| 11 | `Iweet Whatsapp` | `6,421,130,441` | 0.819 | 保留 |
| 12 | `The Canadian government` | `178,461,355,475` | 0.878 | **错误排除** |
| 13 | `has awarded CS20 million` | `178,479,351,491` | 0.883 | **错误排除** |
| 14 | `(USS19.4million)over the` | `176,497,350,511` | 0.844 | **错误排除** |
| 15 | `next four years to five centres of the` | `131,517,373,530` | 0.870 | **错误排除** |
| 16 | `African Institute for Mathematical` | `130,535,361,547` | 0.900 | **错误排除** |
| 17 | `Sciences.The centres are spread` | `130,554,347,569` | 0.885 | **错误排除** |
| 18 | `across the continent and run through the AIMS-Next` | `8,573,359,587` | 0.893 | 保留 |
| 19 | `Einstein Initiative.They will train talented young` | `7,592,337,607` | 0.871 | 保留 |
| 20 | `African postgraduate rescarchers in mathematical` | `7,610,345,626` | 0.869 | 保留 |
| 21 | `sciences.` | `7,629,65,642` | 0.840 | 保留 |
| 22 | `The funding for AIMS was championed by the Ontario` | `8,666,374,682` | 0.859 | 保留 |
| 23 | `based non-profit Perimeter Institute for Theoretical` | `8,686,352,701` | 0.870 | 保留 |
| 24 | `Physics'new global outreach programme headed by` | `8,705,357,720` | 0.877 | 保留 |
| 25 | `South African-born scientist Dr Neil Turok,who is also` | `7,723,373,737` | 0.885 | 保留 |
| 26 | `the founder of AIMS and initiator of the AIMS-Next` | `8,742,349,754` | 0.858 | 保留 |
| 27 | `Einstein Initiative.` | `7,761,130,773` | 0.855 | 保留 |
| 28 | `Privacy` | `325,809,373,828` | 0.898 | 排除 |
| 29 | `Ai` | `181,843,204,865` | 0.727 | 排除 |

OCR 主要字符错误为：`C$20 -> CS20`、`US$19.4 -> USS19.4`、`researchers -> rescarchers`、`Tweet -> Iweet`。其中前两项会直接改变金额币种，必须在翻译前复核。

更严重的问题发生在内容过滤：第一段正文共有 10 行，当前只保留后 4 行，召回率为 40%；两段正文合计 16 行，只保留 10 行，召回率为 62.5%。原因是正文绕左侧图片排版，前 6 行从 `x=130~178` 起始，而过滤器只接受接近主正文左边界 `x≈7` 的行。

## 当前图片翻译的组级请求与响应

以下 8 个语义组就是本次发送给自建服务的有效内容。完整字段、成员区域、置信度和布局提示见 `static-image-report.json`。

| 组 | role | 请求原文 | 服务输出 | 结果 |
|---|---|---|---|---|
| 0 | BODY | `Global Edition Africa Edition` | `全球版 非洲版` | 成功，1 行 |
| 1 | BODY | `AFRICA` | `非洲` | 成功，1 行 |
| 2 | TITLE | `AFRICA-CANADA:Boost for Next` | `加拿大 - 非洲：为未来注入动力` | 成功，但标题被错误拆组 |
| 3 | BODY | `Einstein centres` | `爱因斯坦中心` | 成功，但应与上一组构成完整标题 |
| 4 | BODY | `Munyaradzi Makoni 18 July 2010` | `穆纳拉兹迪·马科尼 2010年7月18日` | 成功，但作者元数据不应默认翻译 |
| 5 | TITLE | `Iweet Whatsapp` | 原文 | `LOCAL_TRANSLATION_FAILED`，角色也误判 |
| 6 | BODY | 第一段最后 4 行 | `遍布整个大陆，并贯穿“非洲数学科学研究所 - 下一代爱因斯坦计划”。它们将培训有才华的年轻非洲研究生从事数学科学研究。` | 成功，但前 6 行已在过滤阶段丢失 |
| 7 | BODY | 第二段 6 行 | `对非洲数学科学研究所（AIMS）的资金支持由位于安大略省的“理论物理中心”非营利组织的全球推广项目所倡导，该项目由南非出生的科学家尼尔·图罗克博士领导。他也是非洲数学科学研究所的创始人和“非洲数学科学研究所 - 下一代爱因斯坦计划”的发起人。` | 成功，术语仍需改进 |

正式结果页输出：

```text
完成，共替换 6 段文字；2 段翻译失败
```

保存图中的实际效果：

- 导航 `Global Edition Africa Edition` 回贴为 `全球版 非洲版`。
- 标题被拆为两块，第一行原文没有完全擦除，形成中英文残留。
- 作者保留原文，没有采用服务返回的中文姓名；这是视觉结果上的正确行为，但协议角色仍不正确。
- 第一段前 6 行保持英文，只翻译从 `across the continent...` 开始的后 4 行。
- 第二段完整回贴，语义明显优于端侧降级译文。
- `Privacy` 没有形成大灰块，正式图片翻译的受保护区域处理优于对照 A。

## 三路结果对比

### 对照 A：服务成功，译文较好，但回贴越界

对照 A 对应服务审计：

```text
request_id=c3beeaec-8ce9-482d-9c84-e08bef8679ac
scene=LIVE_SCREEN
group_count=15
region_count=15
input_chars=785
status=SUCCEEDED
duration_ms=54625
端侧显示总耗时=55317ms
```

优点：

- 第一段金额基本正确：`2000万加元（合1940万美元）`。
- 两段正文均有译文，整体语义是三路中最完整的。
- 人名、机构和上下文连贯性显著优于端侧分片翻译。

问题：

- 正文被压缩成过小字号，第二段几乎不可读。
- `Privacy` 被扩展成占据整段正文宽高的大灰块，覆盖了不属于它的区域。
- 标题、作者、按钮等角色没有稳定区分，作者被翻译。
- 回贴区域为了容纳译文跨越原语义组边界，违反“不覆盖相邻内容”的安全约束。

### 对照 B：服务失败后降级，回贴较稳，但译文不可用

对照 B 对应服务审计：

```text
request_id=ae8f512c-199d-4c2c-b1a1-b1c610c9f029
scene=LIVE_SCREEN
group_count=14
region_count=14
input_chars=786
status=FAILED
duration_ms=51511
端侧显示总耗时=52453ms
```

优点：

- 各译文块大体停留在原段落范围内，没有出现对照 A 的巨型 `Privacy` 覆盖块。
- 作者原文得到保留。

问题：

- 服务失败后静默退回端侧分片翻译，失去整页和整段语义。
- `C$20 million (US$19.4 million)` 被译成 `2000万美元（19400万美元）`，币种和金额均错误。
- `Next Einstein Initiative`、AIMS、机构名称和人名出现机械拼接、重复和错译。
- 第二段存在 `基于 ario` 重复、错误人名等明显不可交付内容。

### 当前正式图片翻译：局部语义和回贴较稳，但正文漏失

优点：

- Qwen 对保留下来的完整第二段提供了三路中最好的组级语义结果。
- 没有跨区扩张 `Privacy`，正式输出图片整体没有大面积错误覆盖。
- 服务成功时没有退回端侧碎片译文。

问题：

- 静态过滤将绕图排版的第一段前 6 行误当作非正文，导致金额句完全没有进入翻译请求。
- 第一段只翻译尾部 4 行，结果完整性低于对照 A。
- 标题两行未合组，原文擦除不完整。
- 术语 `Perimeter Institute for Theoretical Physics` 被译成泛化的“理论物理中心/研究所”，推荐统一为“圆周理论物理研究所”。
- 正式调用 48.188 秒，对手机端仍过慢。

| 维度 | 当前正式图片翻译 | 对照 A：Qwen 成功 | 对照 B：端侧降级 |
|---|---|---|---|
| 正文完整性 | 差：第一段仅 4/10 行进入请求 | 好：两段均翻译 | 好：两段均回贴 |
| 语义质量 | 中上：第二段好，第一段缺失 | 最好，但仍有术语问题 | 差：金额、机构、人名均有严重错误 |
| 几何安全 | 较好，但标题有残留 | 差：`Privacy` 大块越界、正文过度压缩 | 较好 |
| 元数据处理 | 作者视觉上保留，协议仍误分 | 作者被翻译 | 作者保留 |
| 故障可见性 | 服务成功 | 服务成功 | 差：失败后静默产生低质量译文 |
| 总耗时 | 48.188s | 55.317s | 52.453s |

三者都不能直接作为最终方案。应组合“Qwen 整组语义质量”“有界回贴”和“绕图正文完整召回”，而不是从三张结果中任选一条现有链路。

## 人工参考译文

标题：

```text
非洲－加拿大：助力下一代爱因斯坦中心
```

第一段：

```text
加拿大政府将在未来四年向非洲数学科学研究所的五个中心拨款 2000 万加元
（约 1940 万美元）。这些中心分布在非洲大陆各地，并通过 AIMS“下一代
爱因斯坦计划”运营，旨在培养有才华的非洲青年数学科学研究生。
```

第二段：

```text
AIMS 的资助由总部位于安大略省的非营利机构圆周理论物理研究所推动，
具体来自该所新设的全球推广项目。该项目由南非出生的科学家尼尔·图罗克
博士领导；他也是 AIMS 的创始人和 AIMS“下一代爱因斯坦计划”的发起人。
```

## 根因判断

1. **正文漏失不是 OCR 漏识别。** 29 个 OCR 区域中已经包含第一段全部 10 行；错误发生在 OCR 后的 `StaticImageTextFilter`。
2. **当前内容过滤把“主左边界”当成正文必要条件。** 文章首段绕图片形成临时右侧文字通道，前 6 行因此被排除，文字回到全宽后才重新被接受。
3. **对照 A 的主要失败不是翻译。** 它没有对 semantic group、图片、按钮和相邻正文建立不可侵占的占用区域，短标签能够扩张成大补丁。
4. **对照 B 的主要失败不是回贴。** 自建服务失败后退回逐片端侧翻译，丢失整组上下文，且没有金额、实体和术语质量门控。
5. **标题、作者、分享控件的角色仍不足。** 标题两行被拆开；作者被当作 BODY；`Tweet/WhatsApp` 被 OCR 错识别后又当作 TITLE。

## 推荐改造顺序

### P0：同一批改造并联合验证

以下四项直接决定“是否丢正文、是否覆盖页面、是否输出错误译文”，适合放入同一批端到端验证，但代码应保持独立门控：

1. **支持图片绕排的正文流恢复。** 不再用单一左边界过滤正文。根据行高、垂直连续性、上下文标点、水平覆盖和图片占用区，识别“图片右侧临时通道 -> 恢复全宽”的同一段落。本样本必须把 OCR 12-21 合并成一个 BODY 组。
2. **建立不可越界的组级占用契约。** `renderBounds` 默认只能使用成员 OCR 区域联合形成的多段槽位或多边形，加少量内边距；不得扩张进入图片、按钮、相邻组或空白文章区域。补丁只允许在同一 `groupId/sourceGroupIds` 内合并。
3. **长段采用形状感知排版。** 第一段不是单一矩形：前三行位于 `x≈176`，中三行位于 `x≈130`，后四行恢复 `x≈7`。端侧应按这些行槽回流译文，放不下时按“正常 -> 有界字号/行距压缩 -> 更多 -> 保留原文”处理，不能占用其他组。
4. **禁止长正文静默降级成碎片译文。** Qwen 失败时，长 BODY 组保留原文并提示重试/更多；只有短标签或能够维持完整语义组的端侧翻译才允许降级。金额、币种或专名校验失败时也必须拒绝回贴。

P0 验收条件：

```text
第一段正文 OCR 召回：10/10 行
正文语义组：2 个完整段落
标题：两行合为 1 个 TITLE
作者：METADATA/PRESERVED
Tweet、WhatsApp、Privacy：CONTROL/PRESERVED
protected_overlap_px=0
patch_outside_group_px=0
服务失败时：长正文保持原文，不产生低质量分片译文
```

### P1：在 P0 稳定后执行

1. **OCR 关键符号二次校正。** 对金额、币种、百分号和专名执行原图裁片复识别与规则校验：`CS20 -> C$20`、`USS19.4 -> US$19.4`；保留 raw OCR 和 corrected text，不能无记录覆盖。
2. **完善角色。** 新增或稳定使用 `METADATA`、`CONTROL`、`BRAND`；作者名默认保留，日期可以单独本地化；导航是否翻译由策略决定。
3. **服务术语与保真约束。** 固定保留 `AIMS`、`AIMS-Next`、C$/US$ 和数值；术语表加入 `Perimeter Institute for Theoretical Physics = 圆周理论物理研究所`。
4. **基于实测返回布局提示。** `preferredMaxLines` 应由目标字体和真实译文测量得出；对绕图段落返回 `renderSlots`/多边形，而不是一个可以任意扩大的矩形。
5. **补充质量指标。** 至少记录 `body_source_recall`、`wrapped_flow_count`、`protected_overlap_px`、`patch_outside_group_px`、`fallback_provider`、`currency_entity_preserved` 和 `visual_text_coverage`。

### P2：性能优化

三路均为 48-55 秒，当前不适合高频手机端交互。正确性门控稳定后，再通过减少无效组、合并整段请求、压缩提示词、服务预热和受控并发将单页目标收敛到 8-12 秒。不能用重新启用碎片化本地翻译换取表面速度。

## 最终结论

本样本证明当前首要问题已经从“能否整组翻译”转向“能否把完整正文正确送入整组翻译，并在原占用形状内安全回贴”。

- 对照 A 证明 Qwen 整页/整组理解有效，但无边界扩张会破坏页面。
- 对照 B 证明仅保持矩形位置不够，端侧碎片翻译会产生严重语义错误。
- 当前正式图片翻译证明组级 Qwen 与安全回贴方向正确，但单左边界内容过滤不适配图片绕排文章，导致第一段 60% 的行在翻译前丢失。

推荐下一批直接联合实施 P0 的“绕图正文恢复 + 组级占用边界 + 形状感知排版 + 长正文失败门控”，再用本样本做硬性回归。只有同时满足正文 10/10 行进入同一语义组、无受保护区域覆盖、Qwen 失败不输出碎片译文，才算本页通过。

## 证据文件

- `static-image-report.json`：当前图片翻译的完整 OCR、过滤、语义组、译文和布局提示。
- `translation-run-a-ocr-zh.json`：对照 A 中文 OCR 回读。
- `translation-run-b-ocr-zh.json`：对照 B 中文 OCR 回读。
- `static-ui-translated-screen.png`：当前正式结果页完整截图。
- `static-ui-translated-upper.png`、`static-ui-translated-middle.png`：缩放后的局部回贴截图。
- `static-ui-saved-output.jpg`：当前正式图片翻译保存结果。
