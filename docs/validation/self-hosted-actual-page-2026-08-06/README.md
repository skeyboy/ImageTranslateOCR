# V003/V004：真实长文章页自建语义翻译与回贴验证

> 验证日期：2026-08-06  
> 真机：`23113RKC6C`  
> 输入：用户提供的 `388 x 894` 页面截图  
> OCR：Android ML Kit，`AUTO`，沿用正式图片翻译的 3 倍 OCR 放大与坐标回映射  
> 翻译：Axum v2 -> 本地 Ollama `qwen3.5:9b`  
> Android 后端：`SELF_HOSTED`，`http://192.168.0.63:8090`

## 第二轮 P0 改造验证结论

**客户端 P0 合并批次通过。** 本批一次完成正文段落分组、文章 UI 噪声过滤、`layoutHint` 贯通和多行字号计算，并使用同一张真实图片分别保存过滤、分组、服务请求/响应和最终回贴证据。合并实现没有妨碍归因：三项分别由独立 JVM 测试、instrumentation 中间报告和最终 UI 截图验证。

| 指标 | 改造前 | 改造后 | 判定 |
|:---|---:|---:|:---|
| 原始 OCR | 27 行 | 27 行，1,723ms | OCR 未更换，几何基线一致 |
| 默认参与翻译 | 27 regions | 22 regions | 正确排除 5 个非正文/UI 项 |
| 语义组 | 13 组，其中正文 8 组 | 4 个正文段落组，成员数 `6/5/7/4` | 通过 |
| 第一正文段 | 6 行拆成 5 组 | 6 行合为 1 组 | 通过，重复翻译消失 |
| instrumentation 翻译 | 11 succeeded、2 failed | 4/4 succeeded、0 failed | 通过 |
| 正式 UI 回贴 | 替换 10 段、2 段失败 | 替换 4 段、0 段失败 | 通过 |
| 静态图片排版 | 超大字号、裁切、覆盖 | 按约 15-20px 单行高度排版，正文完整可读 | 通过 |
| 正式 UI 服务耗时 | 42,788ms | 25,535ms | 改善 40.3%，仍未达到 8-12s 目标 |
| 热态直接请求 | 38,116ms | 17,141ms | 改善 55.0%，仍需独立性能批次 |

默认过滤明确排除了：`23113RKC6C`、`9:42`、截断浏览器标题 `Xi Story:Six-foot alley revea...`、悬浮控件误识别 `XA EEM` 和底栏 `Ai`。策略只在至少 6 条宽幅左对齐正文行形成文章场景时启用；非文章图片保持原候选列表。复核页将这些项设为默认不参与，用户仍可手动重新勾选。

### 改造后请求与原始输出

正式 instrumentation 请求为 4 groups、22 regions、808 input chars，服务审计耗时 27,830ms；随后用保存的完整请求直接重放，HTTP 200，服务耗时 17,141ms。正式 UI 再次发出 4 groups、22 regions 请求，审计耗时 25,535ms，界面最终显示“完成，共替换 4 段文字”。

| 组/成员 | 请求原文 | `qwen3.5:9b` 原始输出 | layoutHint | 人工核验 |
|:---|:---|:---|:---|:---|
| `semantic-dc5d1bd8` / 6 | `During the Anhui inspection ... fine traditional culture.` | 在安徽考察期间，习近平还强调要加强历史文化遗产保护，推动中华优秀传统文化创造性转化、创新性发展。 | 6 行，scale 0.86，START | 完整且不重复；通过 |
| `semantic-10dfad85` / 5 | `Tongcheng has woven ... before they escalate.` | 桐城将小巷所蕴含的价值融入社区工作和基层治理中，运用说服、共情和妥协等方式帮助化解矛盾于萌芽状态。 | 5 行，scale 0.86，START | 完整连贯；通过 |
| `semantic-3bcf99ec` / 7 | `That emphasis ... law work together.` | 张强强调，这种对包容的强调并不意味着要求人们无原则退让…… | 6 行，scale 0.86，START | 结构完整，但原文只有 `Zhang`，模型无依据扩写为“张强”；不通过实体保真 |
| `semantic-86cb33cc` / 4 | `The approach ... Congress adopted` | 这一做法也获得了更广泛的制度支持。7月24日，安徽省人大常委会通过 | 4 行，scale 0.86，START | 忠实保留截图残句；通过 |

### 改造后原图与回贴

| 原图 | 改造前回贴 | 改造后回贴 |
|:---:|:---:|:---:|
| ![source](./source.png) | ![before](./translated-output.jpg) | ![after](./after-p0-translated-output.jpg) |

改造后所有正文段落均使用成员行高中位数计算字号，服务的 `preferredMaxLines`、`minimumTextScale` 和 alignment 已进入静态图片渲染；最小字号仍不能容纳时会恢复原文，不再提交裁切结果。结果图仍可见两类非本批问题：第三段的“张强”是服务端实体扩写；浮层附近残留一个引号、底部残留 `a`，来自遮挡导致的 OCR/精确遮罩覆盖不完整。

### 合并与后续批次判定

1. **已合并执行：端侧 P0。** 分组、过滤和排版处于同一 OCR -> semantic group -> translate -> render 流水线，适合共享一次真实图片回归；用分层证据保持可归因。
2. **可合并执行：服务提示词质量与提示词瘦身。** 专名不得扩写、输出长度约束和提示词/schema 精简都修改同一模型请求，可作为一个 A/B 批次，同时比较质量与耗时。
3. **不可与提示词批次合并：服务端重分组协议。** `renderGroups/sourceGroupIds` 会改变端云契约和回贴映射，应单独做兼容性与回滚验证。本样本端侧已稳定得到 4 组，不再能有效触发该能力，需要另选欠分组样本。
4. **不可与行为改造合并：运行时/模型性能。** 4B/9B、MLX/llama.cpp 或量化切换会改变性能基线，应在协议和提示词固定后单独测量。

## 改造前基线结论

- **方案契合度：高。** 端侧保留 27 条 OCR 行及原图坐标，服务一次接收 13 个初步语义组和 862 个输入字符；完整段落组能得到连贯、完整且与组 ID 对应的译文。
- **当前语义分组：部分通过。** 第二、第三、第四正文段落分别形成完整组；第一段 6 行被拆成 5 组，服务利用整页上下文提前补齐后文，导致相邻组译文重复。
- **服务协议：通过。** 原始 v2 响应返回 13/13 组、0 failure，成员 ID、锚点和布局提示均与请求对应。
- **Android 结果门控：符合设计但暴露噪声。** `23113RKC6C` 与悬浮控件误识别 `XA EEM` 的服务译文等于原文，被端侧拒绝并回退失败；因此最终为 11 成功、2 失败。
- **实际回贴：不通过。** 正式 UI 显示“替换 10 段、2 段失败”。多行组联合框高度被当成单行字高，`layoutHint` 又在静态图片 UI 链路中丢失，产生超大字号、裁切和覆盖。
- **交互时延：不通过。** 同一页 9B 服务端耗时为 34.477-42.788s；不适合手机端实时或近实时屏幕翻译。

## 原图与实际回贴

| 输入截图 | Android 正式 UI 保存的回贴结果 |
|:---:|:---:|
| ![source](./source.png) | ![translated output](./translated-output.jpg) |

右图可以直接看到：第一段被拆分后出现重复语义；完整段落组“桐城将”“这种”等使用了联合框高度计算字号，中文被放大并严重裁切。该问题不是 9B 原始译文过长造成，而是端侧静态图片渲染仍沿用单行区域字号算法。

## 汇总指标

| 阶段 | 结果 |
|:---|:---|
| 输入图片 | `388 x 894`，SHA-256 `40caf420f34b949266c79c3d040d03af2606ee1b557b7ac2bf8694105ff3da9d` |
| OCR | 27 行，1,573ms |
| 初步语义组 | 13 组；正文理想值为 4 个段落组，当前为 8 个正文组 |
| 真机报告请求 | 13 组、27 regions、862 chars；端侧翻译阶段 34,834ms |
| 真机最终结果 | 11 succeeded、2 failed；其中 1 个时间组保留，实际回贴 10 组 |
| 原始 v2 对照 | HTTP 200；13 results、0 failures；HTTP 38,121ms，服务审计 38,116ms |
| 正式 UI 请求 | 服务审计 42,788ms；界面显示“完成，共替换 10 段文字；2 段翻译失败” |
| 输出图片 | `388 x 894`，SHA-256 `ffc116ca0a83a65ddbe80d4e8ea218ee50cb9bd633242a1b388c6decd059dfd1` |

## OCR 识别结果

| # | OCR 文本 | 置信度 | 坐标 `(left,top)-(right,bottom)` | 人工核验 |
|---:|:---|---:|:---|:---|
| 1 | `23113RKC6C` | 0.851 | `(85,12)-(167,21)` | 设备窗口标题，非页面内容，应过滤 |
| 2 | `9:42` | 0.807 | `(21,43)-(48,54)` | 状态栏时间，识别正确；应过滤或保留不回贴 |
| 3 | `Xi Story:Six-foot alley revea...` | 0.825 | `(112,79)-(296,92)` | 浏览器标题，已截断；不宜作为正文翻译 |
| 4 | `During the Anhui inspection,Xi also` | 0.872 | `(8,115)-(316,135)` | 文字正确，逗号后空格丢失 |
| 5 | `emphasized the need to strengthen the` | 0.905 | `(8,149)-(347,167)` | 正确 |
| 6 | `protection of historical and cultural` | 0.907 | `(8,178)-(310,196)` | 正确 |
| 7 | `heritage,as well as the promotion of` | 0.898 | `(8,209)-(322,226)` | 文字正确，逗号后空格丢失 |
| 8 | `creative transformation and innovative` | 0.901 | `(8,239)-(338,254)` | 正确 |
| 9 | `development of fine traditional culture.` | 0.890 | `(8,269)-(342,286)` | 正确 |
| 10 | `Tongcheng has woven the values` | 0.903 | `(7,318)-(292,336)` | 正确 |
| 11 | `associated with the alley into community` | 0.902 | `(8,348)-(359,366)` | 正确 |
| 12 | `work and grassroots governance,using` | 0.887 | `(7,378)-(344,398)` | 文字正确，逗号后空格丢失 |
| 13 | `persuasion,empathy and compromise to` | 0.873 | `(9,409)-(358,428)` | 文字正确，标点后空格丢失 |
| 14 | `help resolve disputes before they escalate.` | 0.899 | `(9,440)-(373,457)` | 正确 |
| 15 | `That emphasis on accommodation,Zhang` | 0.886 | `(7,488)-(367,506)` | 文字正确，逗号后空格丢失 |
| 16 | `stressed,does not mean asking people to` | 0.887 | `(8,518)-(366,535)` | 文字正确，逗号后空格丢失 |
| 17 | `yield without principle."It's not simply` | 0.840 | `(8,547)-(334,567)` | 正确 |
| 18 | `giving way,still less forcing someone to do` | 0.873 | `(8,578)-(375,596)` | 文字正确，逗号后空格丢失 |
| 19 | `so,"he said."It is a gesture the stronger` | 0.834 | `(12,606)-(372,626)` | 丢失标点后空格及 `--` 破折号 |
| 20 | `side takes the first step,while morality and` | 0.902 | `(8,638)-(375,656)` | 文字正确，逗号后空格丢失 |
| 21 | `law work together.` | 0.905 | `(8,668)-(165,687)` | 正确 |
| 22 | `XA EEM` | 0.359 | `(181,678)-(263,695)` | “正在翻译”悬浮控件误识别，应按低置信度/覆盖层过滤 |
| 23 | `The approach is also gaining broader` | 0.883 | `(8,718)-(326,737)` | 正确 |
| 24 | `institutional backing.On July 24,the` | 0.890 | `(8,748)-(322,767)` | 文字正确，句号和逗号后空格丢失 |
| 25 | `Standing Committee of the Anhui` | 0.882 | `(9,779)-(296,798)` | 正确 |
| 26 | `Provincial People's Congress adopted` | 0.882 | `(8,806)-(328,827)` | 正确；原图在句中截断 |
| 27 | `Ai` | 0.706 | `(181,845)-(209,868)` | 浏览器底部控件，应过滤，不应翻译成“艾” |

正文单词基本完整，主要 OCR 损失是英文标点后的空格和一处破折号。几何坐标与实际行位置匹配，可以继续作为端侧回贴依据；主要噪声来自截图中包含的设备窗口标题、状态栏、翻译悬浮控件和浏览器底栏。

## 请求与输出逐组对照

下表的“服务原始输出”来自保存的完整 v2 请求 `request.json` 和响应 `response.json`；“Android 最终状态”来自正式 `TranslateManager` 路由报告。

| 顺序/坐标 | 请求：初步语义组原文 | 服务原始输出 / 布局 | Android 最终状态 | 人工结论 |
|:---|:---|:---|:---|:---|
| 0 `(85,12)-(167,21)` | `23113RKC6C` | `23113RKC6C`；1 行 | `FAILED / LOCAL_TRANSLATION_FAILED` | 非页面内容；服务不改写是合理的，端侧应在请求前过滤 |
| 1 `(21,43)-(48,54)` | `9:42`，`PRESERVED` | `PRESERVED`；1 行、居中 | 成功保留 `9:42` | 协议正确，但状态栏不应进入正文组 |
| 2 `(112,79)-(296,92)` | `Xi Story:Six-foot alley revea...` | `习故事：六尺巷揭...`；1 行 | 成功并回贴 | OCR 本身截断且栏目名不自然；应过滤浏览器标题或识别省略态 |
| 3 `(8,115)-(316,135)` | `During the Anhui inspection,Xi also` | `在安徽考察期间，习近平还`；1 行 | 成功并回贴 | 第一段被拆开，译文成为悬空分句 |
| 4 `(8,149)-(347,196)` | `emphasized the need to strengthen the`<br>`protection of historical and cultural` | `强调要加强历史文化遗产保护，以及推动优秀传统文化的创造性转化和创新性发展。`；2 行 | 成功并回贴 | 服务利用整页上下文越过组边界补齐了后 3 组内容，造成重复 |
| 5 `(8,209)-(322,226)` | `heritage,as well as the promotion of` | `遗产的保护，同时促进`；1 行 | 成功并回贴 | 与组 4 语义重复，且本身是片段 |
| 6 `(8,239)-(338,254)` | `creative transformation and innovative` | `创造性转化和创新性发展。`；1 行 | 成功并回贴 | 与组 4 重复 |
| 7 `(8,269)-(342,286)` | `development of fine traditional culture.` | `优秀传统文化的传承与发展。”`；1 行 | 成功并回贴 | 与组 4 重复并出现无来源右引号 |
| 8 `(7,318)-(373,457)` | 5 行完整 `Tongcheng ... escalate.` 段落 | `桐城将小巷所蕴含的价值融入社区工作和基层治理，运用说服、共情和妥协的方式帮助化解矛盾于萌芽状态。`；5 行 | 成功并回贴 | 译文完整连贯；语义层通过，实际排版因字号算法严重失败 |
| 9 `(7,488)-(375,687)` | 7 行完整 `That emphasis ... law work together.` 段落 | `张强调，这种对包容的强调并不意味着要求人们无原则地退让。“这不仅仅是让步，更不是强迫别人这样做，”他说，“这是强势一方主动迈出的一步，同时道德与法律共同发挥作用。”`；建议 6 行 | 成功并回贴 | 完整性和上下文较好；`accommodation` 可进一步译为“礼让/互谅互让”；实际排版失败 |
| 10 `(181,678)-(263,695)` | `XA EEM` | `XA EEM`；1 行 | `FAILED / LOCAL_TRANSLATION_FAILED` | 悬浮控件噪声，必须在 OCR 后过滤 |
| 11 `(8,718)-(328,827)` | 4 行 `The approach ... Congress adopted` | `这一做法也获得了更广泛的制度支持。7月24日，安徽省人大常委会通过`；4 行 | 成功并回贴 | 对当前可见残句忠实，没有臆造被截图截掉的宾语；实际排版失败 |
| 12 `(181,845)-(209,868)` | `Ai` | `艾`；1 行、居中 | 成功并回贴 | 浏览器底部控件被错误翻译，端侧内容区域过滤不足 |

## 根因分析

### 1. 初步语义组仍受缩放后行距阈值影响

第一段相邻行的回映射间距为 11-15px，行高约 15-20px。当前跨 block/无 block 的最大间距为 `0.68 x 行高`，只有第 5、6 行满足，最终 6 行被拆成 5 组。第二、三、四段的行距较稳定，能分别形成完整组。

服务按输入 `groupId` 返回，不能主动把 3-7 合并成一个新的回贴组。它只能利用 `documentContext` 改写每个既有组，因此出现“组 4 提前补全、组 5-7 再次翻译”的重复。

### 2. v2 协议能绑定，但还不能表达服务端重分组

当前协议验证了以下能力：

- 服务接收整页 `documentContext`、viewport、13 个 groups 和 27 个带坐标 regions；
- 响应严格回传原 `groupId`、`memberRegionIds`、`anchorBounds` 和 `layoutHint`；
- Android 能拒绝未发生有效翻译的噪声组，并对失败组本地回退。

如果目标是“Pnuts 负责整页理解”，下一版协议必须允许服务返回可追溯的合并决定，例如 `renderGroups[]` 携带 `sourceGroupIds`、合并后的 `memberRegionIds`、译文、锚点和布局提示。否则服务只有全文上下文，没有修正端侧欠分组的表达能力。

### 3. 静态图片 UI 丢失组级布局提示

`TranslateManager` 已返回 `layoutHint`，但 `ImageTranslateActivity` 构造 `TranslatedRegion` 时只保留文本和成功状态。`drawTexts` 随后使用 `group.unionBounds.height` 作为单行基础字号，并设置最小字号为 `bounds.height * 0.68`。

对于 5 行组 `(7,318)-(373,457)`，联合框高为 139px，而真实单行高度只有约 18px；最小字号因此被错误抬到约 95px。结果就是输出图中的“桐城将”等超大文字、裁切和相互覆盖。

### 4. 9B 整页请求时延不适合交互

13 组、862 字符的多次服务审计为 31.8-42.8s；正式 UI 本次为 42.788s。单次整页请求避免了 N 次网络调用，但当前 Ollama 9B 推理仍远超手机屏幕翻译可接受范围。

## 推荐改造顺序

1. **P0：修复组级排版。** 将 `layoutHint` 传到静态图片渲染；字号基线使用成员行高的中位数而不是联合框高度，并按 `preferredMaxLines`、`minimumTextScale` 做真实排版测量。任何文本不得在裁切状态下提交回贴。
2. **P0：增加内容区域与覆盖层过滤。** 排除设备窗口、状态栏、浏览器底栏和翻译悬浮控件；低置信度只能作为辅助信号，不能单独过滤高置信度设备 ID。
3. **P0：把第一段稳定合并为一个正文组。** 对无 block ID 的连续正文采用行高归一化行距、共同左右边界和段间空白联合判定；本样本应从 8 个正文组收敛为 4 个段落组。
4. **P1：扩展可选服务端重分组返回。** v2 保持兼容；新增 `renderGroups/sourceGroupIds` 或 `groupingDecisions`，允许整页理解服务修正欠合并，同时保留每个 OCR region 的几何追溯。
5. **P1：降低推理时延。** 缩短提示词和输出 schema，设置更紧的输出 token 上限；比较 4B 快速首译、9B 质量回退或 MLX/llama.cpp 推理。手机交互目标建议先压到 8-12s，再考虑实时链路。
6. **回归门槛：** 本图 4 个正文段落必须形成 4 个组；`23113RKC6C`、`XA EEM`、`Ai` 不得回贴；13 组原始服务绑定保持 0 failure；最终图片不得出现裁切，正文译文可完整阅读。

## 证据文件

| 文件 | 内容 |
|:---|:---|
| [`source.png`](./source.png) | 用户提供的真实输入图 |
| [`actual-page-report.json`](./actual-page-report.json) | 真机 OCR、语义组和 Android 最终翻译结果 |
| [`request.json`](./request.json) | 根据真实 OCR/分组生成的完整 v2 请求 |
| [`response.json`](./response.json) | Axum/Qwen 原始 v2 响应 |
| [`comparison.json`](./comparison.json) | 请求、服务响应和 Android 结果按 `groupId` 合并后的机器可读对照 |
| [`response.http.txt`](./response.http.txt) | HTTP 状态与耗时 |
| [`translated-output.jpg`](./translated-output.jpg) | 正式 Android UI 保存的最终回贴图 |
| [`ui/result-screen.png`](./ui/result-screen.png) | UI 显示“替换 10 段、2 段失败”的状态证据 |
| [`after-p0-report.json`](./after-p0-report.json) | 改造后真机 OCR、过滤明细、4 个语义组、译文与 layoutHint |
| [`after-p0-request.json`](./after-p0-request.json) | 改造后 4 groups / 22 regions 完整 v2 重放请求 |
| [`after-p0-response.json`](./after-p0-response.json) | 改造后 Axum/Qwen HTTP 200 原始响应 |
| [`after-p0-response.http.txt`](./after-p0-response.http.txt) | 改造后直接请求 HTTP 响应头 |
| [`after-p0-comparison.json`](./after-p0-comparison.json) | 改造前后指标及 4 组请求/响应机器可读对照 |
| [`after-p0-translated-output.jpg`](./after-p0-translated-output.jpg) | 改造后正式 Android UI 保存的 388 x 894 回贴图 |
| [`ui/after-p0-result-expanded.png`](./ui/after-p0-result-expanded.png) | 改造后 UI 完成状态与原图区域 |
| [`ui/after-p0-translated-result.png`](./ui/after-p0-translated-result.png) | 改造后第一、二段回贴 UI 证据 |
| [`ui/after-p0-translated-result-lower.png`](./ui/after-p0-translated-result-lower.png) | 改造后第三、四段回贴 UI 证据 |
