# ragent-core

一个基于 Spring Boot 的企业级 RAG 核心服务，支持文档索引、向量检索、重排序与大模型回答；开箱支持 Ollama 与阿里百炼（百炼），并提供标准 REST 与流式 SSE 输出。

## 核心功能
- 文档索引：支持 PDF / Markdown / DOC / DOCX，自动切分为 chunk 并入库（Milvus 向量库）
- 向量化与检索：通过可配置的 Embedding 服务（默认 Ollama）生成向量，使用 Milvus HNSW + COSINE 进行高效召回
- 结果重排序（Rerank）：支持 BaiLian 与 Ollama 重排模型，提升 TopK 命中质量
- 大模型回答：支持非流式与流式输出（SSE / 纯文本），问题上下文由检索到的 chunk 汇总生成
- 多模型路由：通过配置 `ai.embedding.provider`、`ai.chat.provider`、`ai.rerank.provider` 动态切换厂商与模型

## 组件与接口
- 文档管理接口（`/api/documents`）：
  - POST `/file`：上传并索引文件，form-data: `file` + 可选 `documentId`
  - GET `/{documentId}`：查看该文档的所有 chunk
  - DELETE `/{documentId}`：删除该文档的所有向量
  - PUT `/{documentId}`：更新文档（删除旧向量后重建）
- RAG 问答接口（V1，`/api/ragent/v1/rag`）：
  - GET `/chat?question=...&topK=3`：直接返回字符串答案
  - GET `/stream`：SSE 流式输出（`text/event-stream`）
  - GET `/stream-text`：纯文本流式输出（`text/plain`）
- RAG 问答接口（V2，`/api/ragent/v2/rag`）：与 V1 等价的能力与路径前缀，便于版本演进

## 环境要求
- JDK 17、Maven 3.9+
- Milvus 2.x（示例默认 `http://localhost:19530`）
- 可选：Ollama（本地/私有化模型服务）与阿里百炼（DashScope）账号

## 配置示例（`src/main/resources/application.yaml`）
```yaml
server:
  port: 8080

spring:
  application:
    name: ragent-core

milvus:
  uri: http://localhost:19530

rag:
  collection-name: rag_hnsw_cosine_test
  dimension: 4096
  metric-type: COSINE

ai:
  embedding:
    provider: ollama
    ollama:
      url: http://localhost:11434
      model: qwen3-embedding:8b-fp16

  chat:
    provider: bailian     # 可改为 ollama
    ollama:
      url: http://localhost:11434
      model: qwen3:8b-fp16
    bailian:
      url: https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions
      api-key: sk-xxxxxxxxxxxxxxxxxxxxxxxxxxxx
      model: qwen-plus-latest

  rerank:
    provider: bailian     # 可改为 ollama
    ollama:
      url: http://localhost:11434
      model: dengcao/Qwen3-Reranker-8B:F16
    bailian:
      url: https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank
      api-key: sk-xxxxxxxxxxxxxxxxxxxxxxxxxxxx
      model: qwen3-rerank
```

- 将 `provider` 按需切换为 `ollama` 或 `bailian`
- 替换示例中的 `api-key` 为你的真实密钥
- Milvus 的集合名、维度与度量方式可按自己的数据与模型进行调整

## 启动项目
- 本地启动：
```bash
./mvnw spring-boot:run
```
- 打包运行：
```bash
./mvnw clean package
java -jar target/ragent-core-*.jar
```

## 使用示例
- 文档索引（上传文件并入库）：
```bash
curl -X POST \
  -F "file=@/path/to/your.docx" \
  -F "documentId=doc_001" \
  http://localhost:8080/api/documents/file
```
- 查看文档 chunk：
```bash
curl http://localhost:8080/api/documents/doc_001
```
- 更新文档（重建向量）：
```bash
curl -X PUT \
  -F "file=@/path/to/new.docx" \
  http://localhost:8080/api/documents/doc_001
```
- 删除文档：
```bash
curl -X DELETE http://localhost:8080/api/documents/doc_001
```

- 发起 RAG 问答（非流式）：
```bash
curl "http://localhost:8080/api/ragent/v1/rag/chat?question=公司如何开票?&topK=3"
```
- 发起 RAG 问答（SSE 流式）：
```bash
curl -H "Accept: text/event-stream" \
  "http://localhost:8080/api/ragent/v1/rag/stream?question=公司如何开票?&topK=3"
```
- 发起 RAG 问答（纯文本流式）：
```bash
curl "http://localhost:8080/api/ragent/v1/rag/stream-text?question=公司如何开票?&topK=3"
```

> 说明：V2 接口路径与 V1 等价，只需将路径前缀改为 `/api/ragent/v2/rag` 即可。

## 工作原理（简述）
1. 文档入库：解析上传文件，切分 chunk，调用 Embedding 生成向量并写入 Milvus 集合
2. 召回：问题向量化后在 Milvus 中检索 TopK 结果
3. 重排序：使用 Rerank 模型对粗排结果精排，筛选更相关的 chunk
4. 生成：将 TopK chunk 汇总成上下文，拼接 Prompt，调用 Chat 模型生成答案（支持流式）

## 未来支持（Roadmap）
- 引用与溯源：返回答案所依据的文档片段与 `doc_id`，便于前端展示引用
- 元数据过滤与命名空间：按文档类型、标签、业务线进行检索过滤
- 更多模型与厂商：OpenAI / DeepSeek / Qwen-Long 等多模型路由与组合
- 多模态支持：图片、表格、代码块的解析与检索
- 索引策略优化：更智能的切分、摘要与段落关联，支持批量与增量索引
- 评测与监控：召回率、精准率、延迟与成本的监控面板与离线评测工具
- 权限与隔离：面向企业的权限控制与数据隔离



## Milvus 集合准备
为保证索引与检索正常工作，需要在 Milvus 中提前创建集合与索引，字段需与代码一致：
- doc_id：VarChar，主键（建议 max_length ≥ 64）
- content：VarChar（建议 max_length ≥ 8192）
- metadata：JSON
- embedding：FloatVector，维度与 `rag.dimension` 保持一致（示例 4096）

索引建议：
- 向量索引：HNSW（metric=COSINE，M=48，efConstruction=300）
- 搜索参数：`ef=128`（本项目检索阶段使用了 ef=128）

示例（使用 Python pymilvus，仅作参考）：
```python
from pymilvus import connections, FieldSchema, CollectionSchema, DataType, Collection, utility

connections.connect(uri="http://localhost:19530")

name = "rag_hnsw_cosine_test"
if utility.has_collection(name):
    utility.drop_collection(name)

fields = [
    FieldSchema(name="doc_id", dtype=DataType.VARCHAR, is_primary=True, max_length=64),
    FieldSchema(name="content", dtype=DataType.VARCHAR, max_length=8192),
    FieldSchema(name="metadata", dtype=DataType.JSON),
    FieldSchema(name="embedding", dtype=DataType.FLOAT_VECTOR, dim=4096),
]
schema = CollectionSchema(fields, description="RAG chunks store")
col = Collection(name, schema)

# 创建 HNSW 索引（Cosine）
col.create_index(
    field_name="embedding",
    index_params={"index_type": "HNSW", "metric_type": "COSINE", "params": {"M": 48, "efConstruction": 300}},
)

# 加载集合
col.load()
```

## 前端流式示例
- SSE（推荐，服务端已返回 `text/event-stream`）：
```html
<script>
  const es = new EventSource('/api/ragent/v1/rag/stream?question=公司如何开票?&topK=3');
  es.onmessage = (e) => { console.log(e.data); };
  es.onerror = (e) => { es.close(); };
</script>
```
- 纯文本流（`/stream-text`）：
```js
fetch('/api/ragent/v1/rag/stream-text?question=公司如何开票?&topK=3')
  .then(res => {
    const reader = res.body.getReader();
    const decoder = new TextDecoder('utf-8');
    return reader.read().then(function process({ done, value }) {
      if (done) return;
      console.log(decoder.decode(value));
      return reader.read().then(process);
    });
  });
```

## 快速验证
- 上传项目内示例文件并入库：
```bash
curl -X POST \
  -F "file=@src/main/resources/file/公司人事/开票信息.md" \
  -F "documentId=invoice_doc" \
  http://localhost:8080/api/documents/file
```
- 提问（非流式）：
```bash
curl "http://localhost:8080/api/ragent/v1/rag/chat?question=公司开票流程是什么?&topK=3"
```
