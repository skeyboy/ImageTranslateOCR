# V001：Pnuts 逐行与语义组翻译对比

> 验证日期：2026-08-05  
> 服务：`https://api-dev.pnutsai.com/api/v1/translate/regions`  
> 方向：英文到中文（`ENGLISH_TO_CHINESE`, `en` -> `zh`）

## 结论

在同一 Pnuts 端点、语种和源文下，6 个 OCR 行片段全部返回 `FAILED`，将它们还原为 1 个完整语义组后返回 `TRANSLATED`。这次结果直接支持当前主线：**先在端侧补齐语义组，再整组翻译和组级回贴**。

影响不只是译文流畅度：在本次请求中，分组直接影响了 Pnuts 质量门控下的可用性。逐行响应没有返回错误码或错误信息，因此尚不能确定其内部失败原因。

## 结果

| 模式 | 请求区域数 | 成功 | 失败 | 单次耗时 | 可用译文 |
|---|---:|---:|---:|---:|---|
| OCR 逐行 | 6 | 0 | 6 | 5,074 ms | 无 |
| 完整语义组 | 1 | 1 | 0 | 4,391 ms | 有 |

语义组译文：

> Web3行业尝试过了，而且我们做得相当不错。直到国家决定投资那些既不适合国家、也不适合Web3社区的项目。然后局势彻底失控。剩下的就是历史了。

自动检查全部通过：逐行结果数量完整，整组翻译成功，且译文保留 `Web3`、“国家”和“历史”等关键信息，也保持了 `neither ... nor ...` 的关系。

## 对照设计

- 逐行组：模拟 OCR 将同一段落拆成 6 行，一次批量发送6 个 region，`useContext=true`。
- 语义组：在端侧先按阅读顺序合并为完整段落，发送 1 个 region，`useContext=false`。
- 两组共用相同的原始文本、翻译方向、保护标识符设置与服务端点。
- 逐行组的 `useContext=true` 不能等同于真正的组级模型输入；当前 v1 请求没有结构化的 `groupId/memberRegionIds`。

## 局限

- 只使用了 1 个合成 OCR 切分样本，不是完整截图数据集，不能代表总体成功率。
- 未执行盲评、黄金译文对齐、过合并率或欠合并率评估。
- 耗时为单次网络请求，不是 P50/P95，不宜据此宣称性能收益。
- 网络与模型输出可能波动，后续必须在固定数据集上重复运行。

## 复现与证据

执行：

```bash
scripts/validate-pnuts-semantic-grouping.sh
```

可用 `PNUTS_TRANSLATION_SERVICE_URL` 覆盖默认服务地址。脚本会重新生成以下文件：

| 文件 | 内容 |
|---|---|
| [`line.request.json`](./line.request.json) | 6 个 OCR 行片段请求 |
| [`line.response.json`](./line.response.json) | Pnuts 逐行响应 |
| [`group.request.json`](./group.request.json) | 1 个完整语义组请求 |
| [`group.response.json`](./group.response.json) | Pnuts 语义组响应 |
| [`summary.json`](./summary.json) | 结果摘要、耗时与自动检查 |
