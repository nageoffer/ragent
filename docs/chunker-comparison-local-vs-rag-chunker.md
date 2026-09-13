# 本地切片器 vs 外部 rag-chunker(PDA) 对照留档

> 评估日期：2026-09-04
> 结论：**不采用外部 rag-chunker 替换本地切片器**，本地块级(块感知)切片继续作为生产默认。
> 详细逐块证据生成于 `rag/target/external-chunker-comparison.md`（可随时复跑重建）。

---

## 一、背景

外部独立仓库 [com.nageoffer.ai:rag-chunker](/home/lyz/code/RAG_chunker) 实现了一版**基于下推自动机（PDA）的行级 Markdown 切片器**
（粗切 `PDA` + 细切 `FineChunker`，宣称目标 200–800 token、10–15% overlap），声称修复了若干行级状态机问题（P1–P7）。
本评估将它与本项目的**块级(块感知)切片器**在相同输入上对照，判断是否值得替换/借鉴。

两条链路：

| | 本地（ragent `rag` 模块） | 外部（rag-chunker jar） |
|---|---|---|
| 思路 | commonmark AST → `Block` 语义块 → 按 Block 类型切草稿 → `ChunkPacker` 按预算打包 | 逐行正则分类 → PDA 栈维护章节路径 → `FineChunker` 聚合/拆分 + overlap |
| 输入 | `MarkdownDocumentParser`（FAST 档，含 GFM 表格） | 原始 Markdown 文本 |
| 预算 | `ChunkBudget.defaults()` = 1024 字符 / overlap 128 / 行 50，容忍倍数 3 | NORMAL 600–1000 字符聚合、CODE≤2000 原子、表每 8 行、overlap 12%（仅同章节） |

## 二、如何复跑

```bash
# 1) 外部 jar 已 install 进 ~/.m2（坐标 com.nageoffer.ai:rag-chunker:1.0.0-SNAPSHOT）
#    rag/pom.xml 已声明 <scope>test</scope> 依赖
# 2) 跑对照测试
./mvnw -pl rag -am -Dtest=ExternalChunkerComparisonTest -Dsurefire.failIfNoSpecifiedTests=false test
# 3) 查看产物
rag/target/external-chunker-comparison.md
```

对照测试：`rag/src/test/java/com/nageoffer/ai/ragent/core/chunk/ExternalChunkerComparisonTest.java`（不做断言，只导出报告）。

样本：本地 fixture `merchant-manual.md`（3373 字符）+ 外部 jar 自带的 `anthropic-chat.md`（2964）、`Ingestion.md`（7524）。

## 三、量化结果

| 文档 | 方案 | 块数 | 平均/最小/最大（字符） | 估算 token |
|---|---|---|---|---|
| merchant-manual | 本地 / 外部粗切 / 外部细切 | 4 / 11 / 10 | 838 / 258 / 1322 · 293 / 36 / 1256 · 317 / 36 / 1255 | 838 · 807 · 793 |
| anthropic-chat | 本地 / 外部粗切 / 外部细切 | 3 / 23 / 21 | 968 / 921 / 1012 · 120 / 28 / 405 · 130 / 28 / 359 | 726 · 691 · 687 |
| Ingestion | 本地 / 外部粗切 / 外部细切 | 6 / 20 / 15 | 1218 / 647 / 2794 · 358 / 42 / 2772 · 496 / 35 / 1105 | 1828 · 1792 · 1860 |

观察：本地产出稳定 ~1k 的语义块（少数整节原子可超预算，如 Ingestion 2794 < 容忍 3072）；
外部在标题密集的真实文档上过度碎化（大量 30–130 token 块，最小 28 字符），达不到其自称目标。

## 四、外部切片问题清单（附证据）

1. **标题永远不进 content** —— PDA 把标题行剔除、只塞进 `sectionPath` 元数据。
   正文是无头孤句：merchant 细切 #3 仅 `佣金率随一级类目与店铺等级变化，下表为企业店的基准费率，保证金单位为元。`（36 字，9 token）。
   块是否携带标题词面完全依赖下游是否拼接 path；本地相反：content 是文档原貌（标题按原文位置在正文内），
   且 `ChunkAssembler` 把正文未覆盖的章节前缀补进 `embeddingText`，块天然自描述。

2. **细切 overlap 制造残片** —— 按字符数截上一块尾部 12%，不感知行边界与类型，只判章节相同即拼接，跨类型照拼：
   - anthropic 细切 #7 开头 = 上一表格块最后一行被切剩的 `ries | int | 2 | Number of retry attempts |`，再接 `> **Note:** ...`
   - anthropic 细切 #10 / #15 开头 = 上一 CODE 块结尾 + 悬挂的 ``` 围栏残渣
   - Ingestion 细切 #8 开头与 #7 尾部重复约 100 字符（同节 content 双写）
   overlap 想防"答案被切断"，实际制造了脏文本进入向量与展示。

3. **过度碎化** —— 细切只在**同一完整 sectionPath** 内聚合，小章节永不跨标题合并到下限：
   anthropic 细切 21 块、平均 130 字符（~30 token），merchant 细切 #0–#2 每节一段 49/54/120 字符。
   块太少扛不住 topK、块太碎则关键词不足。

4. **markdown 噪声原样进 content** —— `>` 引用符（如 `> **Note:**`）、`**` 强调、`\(` / `\.` / `\`` 转义反斜杠、code fence 残留均未清洗；
   本地解析器已剥掉 `>` 与转义、emphasis 只留文本。

5. **图片身份丢失** —— 细切把 IMAGE/LINK/BLOCKQUOTE 并入 NORMAL：merchant #5 的 `![退货退款流转路径](images/refund-flow.png)`
   直接贴在句子尾部（无换行）；细切后类型直方图里 IMAGE 消失。本地保留独立图片块 + asset 元数据。

6. **可维护性** —— 行级正则 + 硬编码魔法数（overlap 12%、表 8 行、600/1000/2000 阈值），
   状态由栈表达、上下文需逐处自洽；本地产物由 AST 驱动、预算/容忍参数受控。

**本地侧观察到的小瑕疵（非 bug）**：`ChunkPacker.merge()` 对合并块取章节路径**公共前缀**，
跨两个同级顶层 `#` 小节合并时公共前缀为空 → `Ingestion.md` 的 #0/#5 块 `outlinePath` 为空（`章节=-`）。
这是"碎块并入整块"取舍的代价，标题字面仍在 content 内，不影响展示与检索（向量前缀为空时回落正文）。

## 五、评分（满分 100，专家主观，非基准数据）

| 维度（权重） | 本地 块级AST | 外部 PDA 细切 |
|---|---:|---:|
| 块自描述·上下文完整 (15) | 9 | 4 |
| 块大小稳定·命中目标 (15) | 9 | 3 |
| 边界质量·无残片 (20) | 9 | 2 |
| 类型与结构保真 (15) | 8 | 5 |
| 检索文本质量 (15) | 9 | 4 |
| 实现成熟度·可维护 (10) | 8 | 6 |
| 工程接入度 (10) | 9 | 3 |
| **加权总分** | **≈ 87.5** | **≈ 37** |

公道话：外部**粗切阶段**本身（行分类 + P1–P7 修复 + 表格/代码分离）约值 ~68，
把总分拉低的是**细切**：碎化 + overlap 残片直接弄脏产出。

## 六、结论与建议

- **不替换**：本地块级切片器在自描述、边界干净、大小稳定、检索文本质量上全面占优，且已接通
  content / embeddingText / metadata / 下游 sink。
- **可借鉴（本地均已覆盖，仅作确认）**：块携带章节路径（本地 `outlinePath` + embedding 前缀）、
  表格按行拆分并保留表头。
- **外部仓库可作 bug 清单与回归输入**：其 sample 文档已纳入本对照的输入集。

## 七、变更状态

- 新增：`rag/src/test/java/com/nageoffer/ai/ragent/core/chunk/ExternalChunkerComparisonTest.java`（对照 harness，未提交）
- 既有未提交改动：`rag/pom.xml` 已含 `rag-chunker` test 依赖（引入时机早于本次评估）
- 未改动任何生产代码；本档未提交
