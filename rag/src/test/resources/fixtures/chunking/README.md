# 分块回归 fixture

`ChunkingFixtureTest` 的输入。**改动这些文件会让断言失效**：多条断言依赖具体字数与句读位置（如代码块须超预算三倍、列表须 18 项、长段落每句须短于回溯距离）。

## 离线可验证

| 文件 | 解析器 | 覆盖的规则 |
|---|---|---|
| `merchant-manual.md` | `MarkdownDocumentParser` | 章节路径累积与硬边界、表格行分组与表头重复、图片资产随合并保留、代码块三倍容忍与按行降级、长列表按十项分组、长段落句末切点、URL 断行复原、CJK 软换行合并 |
| `service-notes.txt` | `MarkdownDocumentParser`（认领 `text/plain`） | 缩进段落被识别成代码块、无标题文档的合并行为、无标点长文本的强制推进 |
| `order-records.csv` | `CsvDocumentParser` | 单张 key-val 表、空值不进向量文本、引号内逗号与转义引号、cell 内竖线转义与换行转 `<br>` |

## 仅供人工端到端

`merchant-manual.pdf` 与 `merchant-manual.docx` 是同一份 markdown 的另两种形态，由 `merchant-manual.md` 生成（pandoc 出 docx，headless Chrome 出 pdf）。这两种 MIME 只被 `MinerUDocumentParser` 认领，依赖外部 SaaS，离线跑不了，只能从知识库上传后人工核对。

同一份内容三种形态，正好可以横向对比 MinerU 与本地解析器在表格、图片、代码块上的差异。

`images/refund-flow.png` 是 markdown 里引用的图，同时被 pdf / docx 内嵌。
