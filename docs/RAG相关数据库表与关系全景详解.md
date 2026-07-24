# RAG相关数据库表与关系全景详解

## 1. 文档目标

本文面向当前 `ragent` 项目的 RAG 主链路，系统梳理项目中与 RAG 直接相关的数据库表、各表职责、表间关系、与后端模块的映射关系，以及这些表如何共同支撑：

- 知识库入库与向量化
- 查询归一化与意图路由
- 会话记忆与反馈闭环
- Trace 可观测性
- ingestion 流水线加工

本文不把 `t_user` 作为 RAG 核心业务表展开，而是默认它作为通用用户域主表存在。

核心 schema 参考：

- [schema_pg.sql](file:///e:/java/workspace/ragent/resources/database/schema_pg.sql)

核心业务代码参考：

- [StreamChatPipeline](file:///e:/java/workspace/ragent/bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/pipeline/StreamChatPipeline.java)
- [JdbcConversationMemoryStore](file:///e:/java/workspace/ragent/bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/memory/JdbcConversationMemoryStore.java)
- [KnowledgeDocumentServiceImpl](file:///e:/java/workspace/ragent/bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/service/impl/KnowledgeDocumentServiceImpl.java)
- [IntentTreeServiceImpl](file:///e:/java/workspace/ragent/bootstrap/src/main/java/com/nageoffer/ai/ragent/ingestion/service/impl/IntentTreeServiceImpl.java)

---

## 2. 总体分层视图

从数据库视角看，项目中的 RAG 表可以分成五大类：

1. 知识库主链路
2. 查询预处理与检索路由
3. 会话记忆与交互反馈
4. 可观测性
5. ingestion 摄取流水线

按业务分层抽象如下：

```text
用户问题
 -> t_query_term_mapping
 -> t_intent_node
 -> 检索知识库
    -> t_knowledge_base
    -> t_knowledge_document
    -> t_knowledge_chunk
    -> t_knowledge_vector
 -> 生成回答
 -> t_conversation / t_message / t_conversation_summary
 -> t_message_feedback
 -> t_rag_trace_run / t_rag_trace_node

知识侧离线准备
 -> t_knowledge_document
 -> t_knowledge_document_chunk_log
 -> t_knowledge_document_schedule
 -> t_knowledge_document_schedule_exec
 -> t_ingestion_pipeline
 -> t_ingestion_pipeline_node
 -> t_ingestion_task
 -> t_ingestion_task_node
```

---

## 3. 全局关系总览

如果只看主干关系，可以先记住下面这张逻辑图：

```text
t_knowledge_base
  1
  ├── n t_knowledge_document
  │       ├── n t_knowledge_chunk
  │       │       └── 1 t_knowledge_vector (逻辑上一一对应，按 chunk.id 关联)
  │       ├── n t_knowledge_document_chunk_log
  │       └── 1 t_knowledge_document_schedule
  │               └── n t_knowledge_document_schedule_exec
  │
  └── n t_intent_node

t_intent_node
  1
  └── n t_intent_node (通过 parent_code -> intent_code 自关联形成意图树)

t_conversation
  1
  ├── n t_message
  │       └── n t_message_feedback
  └── n t_conversation_summary

t_rag_trace_run
  1
  └── n t_rag_trace_node

t_ingestion_pipeline
  1
  ├── n t_ingestion_pipeline_node
  └── n t_ingestion_task
          └── n t_ingestion_task_node
```

更贴近实际主链路的理解是：

- `知识库表链路` 负责准备可检索知识
- `意图树 + 术语映射` 负责把问题路由到合适的知识和工具
- `会话表链路` 负责记录输入输出与会话记忆
- `trace 表链路` 负责记录整条调用链的运行过程
- `ingestion 表链路` 负责描述复杂加工型入库流程

---

## 4. 知识库主链路

这一组表构成了 RAG 最核心的数据准备与检索实体链路：

- `t_knowledge_base`
- `t_knowledge_document`
- `t_knowledge_chunk`
- `t_knowledge_vector`
- `t_knowledge_document_chunk_log`
- `t_knowledge_document_schedule`
- `t_knowledge_document_schedule_exec`

### 4.1 `t_knowledge_base`

定义位置：

- [schema_pg.sql#L116-L129](file:///e:/java/workspace/ragent/resources/database/schema_pg.sql#L116-L129)
- [KnowledgeBaseDO](file:///e:/java/workspace/ragent/bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/dao/entity/KnowledgeBaseDO.java)

表职责：

- 知识域根实体
- 描述一个逻辑知识库的基本属性
- 负责把“业务上一个知识库”的概念映射为：
  - 一个知识空间名 `collection_name`
  - 一个嵌入模型 `embedding_model`

关键字段：

- `id`
  - 知识库主键
- `name`
  - 知识库名称
- `embedding_model`
  - 当前库默认使用的嵌入模型
- `collection_name`
  - 向量存储与检索空间标识
- `created_by / updated_by`
  - 审计信息
- `deleted`
  - 逻辑删除

设计意义：

- `knowledge` 模块把知识库视为业务管理单元
- 但真正的向量检索空间通过 `collection_name` 映射到下层存储
- 这实现了“业务知识库概念”与“物理向量空间概念”的衔接

对应模块：

- [KnowledgeBaseServiceImpl](file:///e:/java/workspace/ragent/bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/service/impl/KnowledgeBaseServiceImpl.java)

---

### 4.2 `t_knowledge_document`

定义位置：

- [schema_pg.sql#L131-L156](file:///e:/java/workspace/ragent/resources/database/schema_pg.sql#L131-L156)
- [KnowledgeDocumentDO](file:///e:/java/workspace/ragent/bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/dao/entity/KnowledgeDocumentDO.java)

表职责：

- 知识库内文档的元数据主表
- 也是文档处理配置、处理状态和调度入口的聚合点

关键字段：

- `kb_id`
  - 所属知识库
- `doc_name / file_url / file_type / file_size`
  - 文档静态元信息
- `enabled`
  - 是否参与检索
- `chunk_count`
  - 分块数量
- `process_mode`
  - `chunk` 或 `pipeline`
- `status`
  - `pending / running / success / failed`
- `source_type / source_location`
  - 文档来源，支持文件或 URL
- `schedule_enabled / schedule_cron`
  - 定时刷新配置
- `chunk_strategy / chunk_config`
  - 分块策略和配置
- `pipeline_id`
  - 若走 ingestion 流水线模式，则绑定该 pipeline

设计意义：

- 这张表是 `knowledge` 与 `ingestion` 的连接点
- 它既承载业务上的“文档资产管理”
- 也承载底层加工模式选择
- 所以它不是简单文件表，而是“文档处理控制面”

对应模块：

- [KnowledgeDocumentServiceImpl](file:///e:/java/workspace/ragent/bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/service/impl/KnowledgeDocumentServiceImpl.java)

---

### 4.3 `t_knowledge_chunk`

定义位置：

- [schema_pg.sql#L158-L175](file:///e:/java/workspace/ragent/resources/database/schema_pg.sql#L158-L175)
- [KnowledgeChunkDO](file:///e:/java/workspace/ragent/bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/dao/entity/KnowledgeChunkDO.java)

表职责：

- 存储文档切块后的文本单元
- 是检索上下文的业务文本来源

关键字段：

- `kb_id`
  - 所属知识库
- `doc_id`
  - 所属文档
- `chunk_index`
  - 文档内序号
- `content`
  - 分块正文
- `content_hash`
  - 内容幂等标识
- `char_count / token_count`
  - 统计信息
- `enabled`
  - 分块是否可用于检索

设计意义：

- 检索不是直接对文档做，而是对 chunk 做
- `chunk` 是 RAG 的最小知识单元
- 文本、向量、检索结果和引用定位都围绕 chunk 展开

对应模块：

- [KnowledgeChunkServiceImpl](file:///e:/java/workspace/ragent/bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/service/impl/KnowledgeChunkServiceImpl.java)

---

### 4.4 `t_knowledge_vector`

定义位置：

- [schema_pg.sql#L422-L438](file:///e:/java/workspace/ragent/resources/database/schema_pg.sql#L422-L438)
- [PgVectorStoreService](file:///e:/java/workspace/ragent/bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/vector/PgVectorStoreService.java)

表职责：

- pgvector 模式下的物理向量落表

关键字段：

- `id`
  - 逻辑上直接对应 `chunk.id`
- `collection_name`
  - 所属知识空间
- `content`
  - 文本副本
- `metadata`
  - 元数据，例如 docId、chunkIndex 等
- `embedding`
  - 向量

设计意义：

- `t_knowledge_chunk` 是业务文本表
- `t_knowledge_vector` 是物理向量索引表
- 两者解耦后，系统可切换：
  - `pgvector`
  - `Milvus`

对应模块：

- 写入： [PgVectorStoreService](file:///e:/java/workspace/ragent/bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/vector/PgVectorStoreService.java)
- 检索： [PgRetrieverService](file:///e:/java/workspace/ragent/bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/PgRetrieverService.java)

---

### 4.5 `t_knowledge_document_chunk_log`

定义位置：

- [schema_pg.sql#L177-L197](file:///e:/java/workspace/ragent/resources/database/schema_pg.sql#L177-L197)
- [KnowledgeDocumentChunkLogDO](file:///e:/java/workspace/ragent/bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/dao/entity/KnowledgeDocumentChunkLogDO.java)

表职责：

- 记录单次分块/向量化任务的执行日志

关键字段：

- `doc_id`
- `status`
- `process_mode`
- `chunk_strategy`
- `pipeline_id`
- `extract_duration / chunk_duration / embed_duration / persist_duration / total_duration`
- `chunk_count`
- `error_message`

设计意义：

- 文档处理不是黑盒
- 每次处理都留下执行统计与失败信息
- 有利于后台排障和性能分析

---

### 4.6 `t_knowledge_document_schedule`

定义位置：

- [schema_pg.sql#L199-L221](file:///e:/java/workspace/ragent/resources/database/schema_pg.sql#L199-L221)
- [KnowledgeDocumentScheduleDO](file:///e:/java/workspace/ragent/bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/dao/entity/KnowledgeDocumentScheduleDO.java)

表职责：

- URL 文档的定时刷新主表

关键字段：

- `doc_id`
  - 唯一绑定文档
- `kb_id`
  - 冗余知识库 ID，便于检索与管理
- `cron_expr / enabled`
  - 调度配置
- `next_run_time / last_run_time / last_success_time`
  - 调度时间状态
- `last_status / last_error`
  - 上次运行结果
- `last_etag / last_modified / last_content_hash`
  - 内容变更检测依据
- `lock_owner / lock_until`
  - 分布式调度锁

设计意义：

- 它不是普通 cron 表
- 而是“文档刷新 + 远程变更检测 + 分布式锁”三者合一的控制表

---

### 4.7 `t_knowledge_document_schedule_exec`

定义位置：

- [schema_pg.sql#L223-L242](file:///e:/java/workspace/ragent/resources/database/schema_pg.sql#L223-L242)
- [KnowledgeDocumentScheduleExecDO](file:///e:/java/workspace/ragent/bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/dao/entity/KnowledgeDocumentScheduleExecDO.java)

表职责：

- 每次定时刷新的执行历史

关键字段：

- `schedule_id`
- `doc_id`
- `kb_id`
- `status / message`
- `file_name / file_size`
- `content_hash / etag / last_modified`
- `start_time / end_time`

设计意义：

- 主表记录“当前状态”
- exec 表记录“历史轨迹”
- 二者配合后，调度系统既可做当前控制，也可做审计追溯

---

## 5. 查询预处理与检索路由

这一组表决定“问题怎么被标准化，以及最终走到哪个知识库/工具”。

### 5.1 `t_intent_node`

定义位置：

- [schema_pg.sql#L248-L272](file:///e:/java/workspace/ragent/resources/database/schema_pg.sql#L248-L272)
- [IntentNodeDO](file:///e:/java/workspace/ragent/bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/dao/entity/IntentNodeDO.java)

表职责：

- RAG 意图树配置表
- 决定一个问题最终：
  - 查哪个知识库
  - 用哪个 collection
  - topK 是多少
  - 是否走 system-only
  - 是否走 MCP

关键字段：

- `intent_code`
  - 业务唯一标识
- `parent_code`
  - 父节点编码
- `level`
  - 层级：DOMAIN / CATEGORY / TOPIC
- `kb_id`
  - 关联知识库
- `collection_name`
  - 直接指定检索集合
- `top_k`
  - 节点级召回数
- `kind`
  - KB / SYSTEM / MCP
- `prompt_snippet / prompt_template / param_prompt_template`
  - 节点级提示词控制
- `enabled`
  - 是否启用

关系说明：

- 通过 `parent_code -> intent_code` 实现树形自关联
- 同时通过 `kb_id` 与知识库体系连接

对应模块：

- [IntentTreeServiceImpl](file:///e:/java/workspace/ragent/bootstrap/src/main/java/com/nageoffer/ai/ragent/ingestion/service/impl/IntentTreeServiceImpl.java)
- [IntentTreeCacheManager](file:///e:/java/workspace/ragent/bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/intent/IntentTreeCacheManager.java)

---

### 5.2 `t_query_term_mapping`

定义位置：

- [schema_pg.sql#L274-L291](file:///e:/java/workspace/ragent/resources/database/schema_pg.sql#L274-L291)
- [QueryTermMappingDO](file:///e:/java/workspace/ragent/bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/dao/entity/QueryTermMappingDO.java)

表职责：

- 查询归一化映射表
- 负责把用户口语、别名、内部简称改写为标准术语

关键字段：

- `domain`
  - 领域
- `source_term`
  - 原始词
- `target_term`
  - 目标词
- `match_type`
  - 匹配类型
- `priority`
  - 执行优先级
- `enabled`
  - 是否生效

设计意义：

- 把稳定、可配置的术语归一化从 LLM 改写里前置出来
- 降低改写成本与不确定性

对应模块：

- [QueryTermMappingService](file:///e:/java/workspace/ragent/bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/rewrite/QueryTermMappingService.java)

---

## 6. 会话记忆与交互反馈

这一组表构成了用户交互域，是 chat 业务真正的记忆层。

### 6.1 `t_conversation`

定义位置：

- [schema_pg.sql#L32-L52](file:///e:/java/workspace/ragent/resources/database/schema_pg.sql#L32-L52)
- [ConversationDO](file:///e:/java/workspace/ragent/bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/dao/entity/ConversationDO.java)

表职责：

- 会话目录表

关键字段：

- `conversation_id`
  - 会话业务 ID
- `user_id`
  - 用户 ID
- `title`
  - 会话标题
- `last_time`
  - 最近消息时间

关系：

- `conversation_id + user_id` 唯一
- 1 对多 `t_message`
- 1 对多 `t_conversation_summary`

设计意义：

- 把“会话目录”从“消息明细”里拆出来
- 提升列表页查询效率

---

### 6.2 `t_message`

定义位置：

- [schema_pg.sql#L67-L82](file:///e:/java/workspace/ragent/resources/database/schema_pg.sql#L67-L82)
- [ConversationMessageDO](file:///e:/java/workspace/ragent/bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/dao/entity/ConversationMessageDO.java)

表职责：

- 聊天消息明细表

关键字段：

- `conversation_id`
- `user_id`
- `role`
  - `user / assistant`
- `content`
  - 正文
- `thinking_content`
  - 思考流
- `thinking_duration`
  - 思考耗时

设计意义：

- 是会话记忆的事实表
- 当前用户问题与 assistant 回答都会落这里

对应模块：

- [JdbcConversationMemoryStore](file:///e:/java/workspace/ragent/bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/memory/JdbcConversationMemoryStore.java)

---

### 6.3 `t_conversation_summary`

定义位置：

- [schema_pg.sql#L54-L66](file:///e:/java/workspace/ragent/resources/database/schema_pg.sql#L54-L66)
- [ConversationSummaryDO](file:///e:/java/workspace/ragent/bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/dao/entity/ConversationSummaryDO.java)

表职责：

- 会话摘要表

关键字段：

- `conversation_id`
- `last_message_id`
  - 摘要截止到哪条消息
- `content`
  - 摘要内容

设计意义：

- 长对话上下文不可能无限拼接
- 所以需要通过摘要压缩历史记忆

---

### 6.4 `t_message_feedback`

定义位置：

- [schema_pg.sql#L83-L98](file:///e:/java/workspace/ragent/resources/database/schema_pg.sql#L83-L98)
- [MessageFeedbackDO](file:///e:/java/workspace/ragent/bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/dao/entity/MessageFeedbackDO.java)

表职责：

- 记录用户对 assistant 消息的点赞/点踩反馈

关键字段：

- `message_id`
- `conversation_id`
- `user_id`
- `vote`
- `reason`
- `comment`

关系：

- `(message_id, user_id)` 唯一
- 从“当前用户视角”看，一条消息最多一条反馈

设计意义：

- 反馈不直接改消息表，而是单独建表
- 保持消息事实与交互行为解耦

---

### 6.5 `t_sample_question`

定义位置：

- [schema_pg.sql#L100-L110](file:///e:/java/workspace/ragent/resources/database/schema_pg.sql#L100-L110)
- [SampleQuestionDO](file:///e:/java/workspace/ragent/bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/dao/entity/SampleQuestionDO.java)

表职责：

- 首页或引导页示例问题

说明：

- 它属于 RAG 交互辅助表
- 不参与核心检索、路由、生成链路

---

## 7. 可观测性链路

### 7.1 `t_rag_trace_run`

定义位置：

- [schema_pg.sql#L293-L315](file:///e:/java/workspace/ragent/resources/database/schema_pg.sql#L293-L315)
- [RagTraceRunDO](file:///e:/java/workspace/ragent/bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/dao/entity/RagTraceRunDO.java)

表职责：

- 记录一次完整 RAG 调用链的运行结果

关键字段：

- `trace_id`
- `trace_name`
- `entry_method`
- `conversation_id`
- `task_id`
- `user_id`
- `status`
- `error_message`
- `start_time / end_time / duration_ms`
- `extra_data`

设计意义：

- 它是链路级根记录
- 用来承载“这次 chat 全局发生了什么”

---

### 7.2 `t_rag_trace_node`

定义位置：

- [schema_pg.sql#L316-L338](file:///e:/java/workspace/ragent/resources/database/schema_pg.sql#L316-L338)
- [RagTraceNodeDO](file:///e:/java/workspace/ragent/bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/dao/entity/RagTraceNodeDO.java)

表职责：

- 记录链路内部节点执行情况

关键字段：

- `trace_id`
- `node_id`
- `parent_node_id`
- `depth`
- `node_type / node_name`
- `class_name / method_name`
- `status`
- `duration_ms`
- `error_message`
- `extra_data`

设计意义：

- `run` 表回答“整条链路如何”
- `node` 表回答“链路内部每一段如何”

---

## 8. ingestion 摄取流水线

这部分表属于知识加工引擎层，但和 RAG 数据准备高度相关。

### 8.1 `t_ingestion_pipeline`

定义位置：

- [schema_pg.sql#L343-L355](file:///e:/java/workspace/ragent/resources/database/schema_pg.sql#L343-L355)
- [IngestionPipelineDO](file:///e:/java/workspace/ragent/bootstrap/src/main/java/com/nageoffer/ai/ragent/ingestion/dao/entity/IngestionPipelineDO.java)

表职责：

- 摄取流水线定义主表

---

### 8.2 `t_ingestion_pipeline_node`

定义位置：

- [schema_pg.sql#L356-L373](file:///e:/java/workspace/ragent/resources/database/schema_pg.sql#L356-L373)
- [IngestionPipelineNodeDO](file:///e:/java/workspace/ragent/bootstrap/src/main/java/com/nageoffer/ai/ragent/ingestion/dao/entity/IngestionPipelineNodeDO.java)

表职责：

- 流水线节点定义

关键字段：

- `pipeline_id`
- `node_id`
- `node_type`
- `next_node_id`
- `settings_json`
- `condition_json`

设计意义：

- 流水线不是数组顺序执行，而是基于 `nextNodeId` 拓扑推进

---

### 8.3 `t_ingestion_task`

定义位置：

- [schema_pg.sql#L374-L396](file:///e:/java/workspace/ragent/resources/database/schema_pg.sql#L374-L396)
- [IngestionTaskDO](file:///e:/java/workspace/ragent/bootstrap/src/main/java/com/nageoffer/ai/ragent/ingestion/dao/entity/IngestionTaskDO.java)

表职责：

- 一次真实加工执行实例

关键字段：

- `pipeline_id`
- `source_type / source_location / source_file_name`
- `status`
- `chunk_count`
- `error_message`
- `logs_json / metadata_json`
- `started_at / completed_at`

---

### 8.4 `t_ingestion_task_node`

定义位置：

- [schema_pg.sql#L397-L416](file:///e:/java/workspace/ragent/resources/database/schema_pg.sql#L397-L416)
- [IngestionTaskNodeDO](file:///e:/java/workspace/ragent/bootstrap/src/main/java/com/nageoffer/ai/ragent/ingestion/dao/entity/IngestionTaskNodeDO.java)

表职责：

- 记录 task 级的节点执行结果

关键字段：

- `task_id`
- `pipeline_id`
- `node_id / node_type`
- `node_order`
- `status`
- `duration_ms`
- `message / error_message`
- `output_json`

设计意义：

- pipeline 定义与 runtime 执行解耦
- 有利于失败重试、过程审计和后台展示

---

## 9. 主链路关系梳理

### 9.1 知识准备链路

```text
t_knowledge_base
 -> t_knowledge_document
 -> t_knowledge_chunk
 -> t_knowledge_vector
```

说明：

- `knowledge_base` 定义知识空间
- `document` 管文档元信息和处理配置
- `chunk` 管文本切片
- `vector` 管物理向量索引

这是最核心的“可检索知识单元生产链”。

---

### 9.2 查询路由链路

```text
用户问题
 -> t_query_term_mapping
 -> t_intent_node
 -> 决定检索哪个 kb / collection / topK / MCP
```

说明：

- 查询先被标准化
- 再映射到意图树
- 最终决定走哪种检索与工具路径

---

### 9.3 会话记忆链路

```text
t_conversation
 -> t_message
 -> t_conversation_summary
 -> t_message_feedback
```

说明：

- `conversation` 是目录层
- `message` 是事实层
- `summary` 是压缩记忆层
- `feedback` 是行为附属层

---

### 9.4 可观测性链路

```text
t_rag_trace_run
 -> t_rag_trace_node
```

说明：

- 一条 run 记录一次整体调用
- 多条 node 记录链路细节

---

### 9.5 ingestion 处理链路

```text
t_ingestion_pipeline
 -> t_ingestion_pipeline_node
 -> t_ingestion_task
 -> t_ingestion_task_node
```

说明：

- 前两张表描述“怎么处理”
- 后两张表描述“这次怎么执行了”

---

## 10. RAG 主流程如何落到这些表

结合 [StreamChatPipeline](file:///e:/java/workspace/ragent/bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/pipeline/StreamChatPipeline.java) 和 [JdbcConversationMemoryStore](file:///e:/java/workspace/ragent/bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/memory/JdbcConversationMemoryStore.java)，可以把主流程和表映射成：

### 步骤 1：加载记忆

- 读 `t_message`
- 可能结合 `t_conversation_summary`

### 步骤 2：改写与归一化

- 读 `t_query_term_mapping`

### 步骤 3：意图解析

- 读 `t_intent_node`

### 步骤 4：检索知识

- 通过意图绑定的 `kb_id / collection_name`
- 读 `t_knowledge_base`
- 再读 `t_knowledge_vector`
- 并回溯到 `t_knowledge_chunk`

### 步骤 5：流式生成与落库

- 当前用户问题写 `t_message`
- assistant 回答写 `t_message`
- 会话目录更新 `t_conversation`

### 步骤 6：反馈闭环

- 用户点赞/点踩写 `t_message_feedback`

### 步骤 7：可观测性

- 整条链路写 `t_rag_trace_run`
- 各阶段节点写 `t_rag_trace_node`

---

## 11. 设计特点总结

从表设计可以看出这个项目在 RAG 方向上的几个明显设计思想。

### 11.1 业务知识与物理向量解耦

- `t_knowledge_chunk` 管文本知识单元
- `t_knowledge_vector` 管物理向量索引

好处是：

- 可切换向量后端
- 业务数据与索引数据职责清晰

### 11.2 路由配置前置化

- `t_query_term_mapping`
- `t_intent_node`

说明项目不是把所有决策都交给 LLM，而是：

- 规则前置
- 意图路由前置
- 模型补强

### 11.3 记忆与消息分层

- `t_conversation`
- `t_message`
- `t_conversation_summary`

说明作者对“列表管理”“消息事实”“摘要压缩”做了明确分层，而不是一张大表混装。

### 11.4 可观测性是第一等公民

- 专门有 `t_rag_trace_run` 和 `t_rag_trace_node`

这说明项目不是“能跑就行”，而是把链路观测当成架构一部分。

### 11.5 pipeline 定义与执行分离

- `pipeline / pipeline_node`
- `task / task_node`

这说明 ingestion 模块是“可编排加工引擎”，不是简单文件导入器。

---

## 12. 面试式总结

如果你需要在面试里快速讲清这些表，可以概括成下面这段：

- 项目的 RAG 数据模型分成五层：知识准备、查询路由、会话记忆、可观测性、摄取流水线。知识侧以 `t_knowledge_base -> t_knowledge_document -> t_knowledge_chunk -> t_knowledge_vector` 为主链路，完成从文档资产到可检索向量单元的转换；查询侧用 `t_query_term_mapping` 和 `t_intent_node` 做术语归一化和意图路由；交互侧用 `t_conversation / t_message / t_conversation_summary / t_message_feedback` 承载会话、记忆与反馈；观测侧用 `t_rag_trace_run / t_rag_trace_node` 跟踪整条调用链；复杂入库能力则由 `t_ingestion_pipeline / t_ingestion_task` 这套表支撑。整体上体现的是“业务实体、物理索引、路由配置、会话记忆、执行观测”五层解耦的设计思想。

---

## 13. 关键源码索引

- Schema
  - [schema_pg.sql](file:///e:/java/workspace/ragent/resources/database/schema_pg.sql)
- 会话记忆
  - [JdbcConversationMemoryStore](file:///e:/java/workspace/ragent/bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/memory/JdbcConversationMemoryStore.java)
- RAG 主流水线
  - [StreamChatPipeline](file:///e:/java/workspace/ragent/bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/pipeline/StreamChatPipeline.java)
- 知识库管理
  - [KnowledgeBaseServiceImpl](file:///e:/java/workspace/ragent/bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/service/impl/KnowledgeBaseServiceImpl.java)
  - [KnowledgeDocumentServiceImpl](file:///e:/java/workspace/ragent/bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/service/impl/KnowledgeDocumentServiceImpl.java)
  - [KnowledgeChunkServiceImpl](file:///e:/java/workspace/ragent/bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/service/impl/KnowledgeChunkServiceImpl.java)
- 意图树与归一化
  - [IntentTreeServiceImpl](file:///e:/java/workspace/ragent/bootstrap/src/main/java/com/nageoffer/ai/ragent/ingestion/service/impl/IntentTreeServiceImpl.java)
  - [QueryTermMappingService](file:///e:/java/workspace/ragent/bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/rewrite/QueryTermMappingService.java)
- 可观测性
  - [RagTraceRecordServiceImpl](file:///e:/java/workspace/ragent/bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/impl/RagTraceRecordServiceImpl.java)

