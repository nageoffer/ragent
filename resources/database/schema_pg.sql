-- PostgreSQL Schema for Ragent
-- Converted from MySQL schema_table.sql

-- Enable pgvector extension
CREATE EXTENSION IF NOT EXISTS vector;

-- ============================================
-- User & Conversation Tables
-- ============================================

CREATE TABLE t_user (
    id           VARCHAR(20)  NOT NULL PRIMARY KEY,
    username     VARCHAR(64)  NOT NULL,
    password     VARCHAR(128) NOT NULL,
    role         VARCHAR(32)  NOT NULL,
    avatar       VARCHAR(128),
    create_time  TIMESTAMP  DEFAULT CURRENT_TIMESTAMP,
    update_time  TIMESTAMP  DEFAULT CURRENT_TIMESTAMP,
    deleted      SMALLINT     DEFAULT 0,
    CONSTRAINT uk_user_username UNIQUE (username)
);
COMMENT ON TABLE t_user IS '绯荤粺鐢ㄦ埛琛?;
COMMENT ON COLUMN t_user.id IS '涓婚敭ID';
COMMENT ON COLUMN t_user.username IS '鐢ㄦ埛鍚嶏紝鍞竴';
COMMENT ON COLUMN t_user.password IS '瀵嗙爜';
COMMENT ON COLUMN t_user.role IS '瑙掕壊锛歛dmin/user';
COMMENT ON COLUMN t_user.avatar IS '鐢ㄦ埛澶村儚';
COMMENT ON COLUMN t_user.create_time IS '鍒涘缓鏃堕棿';
COMMENT ON COLUMN t_user.update_time IS '鏇存柊鏃堕棿';
COMMENT ON COLUMN t_user.deleted IS '鏄惁鍒犻櫎 0锛氭甯?1锛氬垹闄?;

CREATE TABLE t_conversation (
    id              VARCHAR(20) NOT NULL PRIMARY KEY,
    conversation_id VARCHAR(20) NOT NULL,
    user_id         VARCHAR(20) NOT NULL,
    title           VARCHAR(128) NOT NULL,
    last_time       TIMESTAMP,
    create_time     TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted         SMALLINT    DEFAULT 0,
    CONSTRAINT uk_conversation_user UNIQUE (conversation_id, user_id)
);
CREATE INDEX idx_user_time ON t_conversation (user_id, last_time);
COMMENT ON TABLE t_conversation IS '浼氳瘽鍒楄〃';
COMMENT ON COLUMN t_conversation.id IS '涓婚敭ID';
COMMENT ON COLUMN t_conversation.conversation_id IS '浼氳瘽ID';
COMMENT ON COLUMN t_conversation.user_id IS '鐢ㄦ埛ID';
COMMENT ON COLUMN t_conversation.title IS '浼氳瘽鍚嶇О';
COMMENT ON COLUMN t_conversation.last_time IS '鏈€杩戞秷鎭椂闂?;
COMMENT ON COLUMN t_conversation.create_time IS '鍒涘缓鏃堕棿';
COMMENT ON COLUMN t_conversation.update_time IS '鏇存柊鏃堕棿';
COMMENT ON COLUMN t_conversation.deleted IS '鏄惁鍒犻櫎 0锛氭甯?1锛氬垹闄?;

CREATE TABLE t_conversation_summary (
    id              VARCHAR(20)      NOT NULL PRIMARY KEY,
    conversation_id VARCHAR(20) NOT NULL,
    user_id         VARCHAR(20) NOT NULL,
    last_message_id VARCHAR(20) NOT NULL,
    content         TEXT        NOT NULL,
    create_time     TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted         SMALLINT    DEFAULT 0
);
CREATE INDEX idx_conv_user ON t_conversation_summary (conversation_id, user_id);
COMMENT ON TABLE t_conversation_summary IS '浼氳瘽鎽樿琛紙涓庢秷鎭〃鍒嗙瀛樺偍锛?;

CREATE TABLE t_message (
    id                VARCHAR(20)      NOT NULL PRIMARY KEY,
    conversation_id   VARCHAR(20) NOT NULL,
    user_id           VARCHAR(20) NOT NULL,
    role              VARCHAR(16) NOT NULL,
    content           TEXT        NOT NULL,
    thinking_content  TEXT,
    thinking_duration INTEGER,
    create_time       TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time       TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted           SMALLINT    DEFAULT 0
);
CREATE INDEX idx_conversation_user_time ON t_message (conversation_id, user_id, create_time);
CREATE INDEX idx_conversation_summary ON t_message (conversation_id, user_id, create_time);
COMMENT ON TABLE t_message IS '浼氳瘽娑堟伅璁板綍琛?;

CREATE TABLE t_message_feedback (
    id              VARCHAR(20)       NOT NULL PRIMARY KEY,
    message_id      VARCHAR(20)       NOT NULL,
    conversation_id VARCHAR(20)  NOT NULL,
    user_id         VARCHAR(20)  NOT NULL,
    vote            SMALLINT     NOT NULL,
    reason          VARCHAR(255),
    comment         VARCHAR(1024),
    create_time     TIMESTAMP  NOT NULL,
    update_time     TIMESTAMP  NOT NULL,
    deleted         SMALLINT     NOT NULL DEFAULT 0,
    CONSTRAINT uk_msg_user UNIQUE (message_id, user_id)
);
CREATE INDEX idx_conversation_id ON t_message_feedback (conversation_id);
CREATE INDEX idx_user_id ON t_message_feedback (user_id);
COMMENT ON TABLE t_message_feedback IS '浼氳瘽娑堟伅鍙嶉琛?;

CREATE TABLE t_sample_question (
    id          VARCHAR(20)        NOT NULL PRIMARY KEY,
    title       VARCHAR(64),
    description VARCHAR(255),
    question    VARCHAR(255) NOT NULL,
    create_time TIMESTAMP   DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP   DEFAULT CURRENT_TIMESTAMP,
    deleted     SMALLINT      DEFAULT 0
);
CREATE INDEX idx_sample_question_deleted ON t_sample_question (deleted);
COMMENT ON TABLE t_sample_question IS '绀轰緥闂琛?;

-- ============================================
-- Knowledge Base Tables
-- ============================================

CREATE TABLE t_knowledge_base (
    id              VARCHAR(20)       NOT NULL PRIMARY KEY,
    name            VARCHAR(128) NOT NULL,
    embedding_model VARCHAR(64)  NOT NULL,
    collection_name VARCHAR(64) NOT NULL,
    created_by      VARCHAR(20)  NOT NULL,
    updated_by      VARCHAR(20),
    create_time     TIMESTAMP  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMP  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted         SMALLINT     NOT NULL DEFAULT 0,
    CONSTRAINT uk_collection_name UNIQUE (collection_name)
);
CREATE INDEX idx_kb_name ON t_knowledge_base (name);
COMMENT ON TABLE t_knowledge_base IS '鐭ヨ瘑搴撹〃';

CREATE TABLE t_knowledge_document (
    id               VARCHAR(20)        NOT NULL PRIMARY KEY,
    kb_id            VARCHAR(20)        NOT NULL,
    doc_name         VARCHAR(256)  NOT NULL,
    enabled          SMALLINT      NOT NULL DEFAULT 1,
    chunk_count      INTEGER       DEFAULT 0,
    file_url         VARCHAR(1024) NOT NULL,
    file_type        VARCHAR(16)   NOT NULL,
    file_size        BIGINT,
    process_mode     VARCHAR(16)   DEFAULT 'chunk',
    status           VARCHAR(16)   NOT NULL DEFAULT 'pending',
    source_type      VARCHAR(16),
    source_location  VARCHAR(1024),
    schedule_enabled SMALLINT,
    schedule_cron    VARCHAR(64),
    chunk_strategy   VARCHAR(32),
    chunk_config     JSONB,
    pipeline_id      VARCHAR(20),
    created_by       VARCHAR(20)   NOT NULL,
    updated_by       VARCHAR(20),
    create_time      TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time      TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted          SMALLINT      NOT NULL DEFAULT 0
);
CREATE INDEX idx_kb_id ON t_knowledge_document (kb_id);
COMMENT ON TABLE t_knowledge_document IS '鐭ヨ瘑搴撴枃妗ｈ〃';

CREATE TABLE t_knowledge_chunk (
    id           VARCHAR(20)      NOT NULL PRIMARY KEY,
    kb_id        VARCHAR(20)      NOT NULL,
    doc_id       VARCHAR(20)      NOT NULL,
    chunk_index  INTEGER     NOT NULL,
    content      TEXT        NOT NULL,
    content_hash VARCHAR(64),
    char_count   INTEGER,
    token_count  INTEGER,
    enabled      SMALLINT    NOT NULL DEFAULT 1,
    created_by   VARCHAR(20) NOT NULL,
    updated_by   VARCHAR(20),
    create_time  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted      SMALLINT    NOT NULL DEFAULT 0
);
CREATE INDEX idx_doc_id ON t_knowledge_chunk (doc_id);
COMMENT ON TABLE t_knowledge_chunk IS '鐭ヨ瘑搴撴枃妗ｅ垎鍧楄〃';

CREATE TABLE t_knowledge_document_chunk_log (
    id                 VARCHAR(20)      NOT NULL PRIMARY KEY,
    doc_id             VARCHAR(20)      NOT NULL,
    status             VARCHAR(16)      NOT NULL,
    process_mode       VARCHAR(16),
    chunk_strategy     VARCHAR(16),
    pipeline_id        VARCHAR(20),
    extract_duration   BIGINT,
    chunk_duration     BIGINT,
    embed_duration     BIGINT,
    persist_duration   BIGINT,
    total_duration     BIGINT,
    chunk_count        INTEGER,
    error_message      TEXT,
    start_time         TIMESTAMP,
    end_time           TIMESTAMP,
    create_time        TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time        TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_doc_id_log ON t_knowledge_document_chunk_log (doc_id);
COMMENT ON TABLE t_knowledge_document_chunk_log IS '鐭ヨ瘑搴撴枃妗ｅ垎鍧楁棩蹇楄〃';

CREATE TABLE t_knowledge_document_schedule (
    id                VARCHAR(20)       NOT NULL PRIMARY KEY,
    doc_id            VARCHAR(20)       NOT NULL,
    kb_id             VARCHAR(20)       NOT NULL,
    cron_expr         VARCHAR(64),
    enabled           SMALLINT     DEFAULT 0,
    next_run_time     TIMESTAMP,
    last_run_time     TIMESTAMP,
    last_success_time TIMESTAMP,
    last_status       VARCHAR(16),
    last_error        VARCHAR(512),
    last_etag         VARCHAR(256),
    last_modified     VARCHAR(256),
    last_content_hash VARCHAR(128),
    lock_owner        VARCHAR(128),
    lock_until        TIMESTAMP,
    create_time       TIMESTAMP  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time       TIMESTAMP  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_doc_id UNIQUE (doc_id)
);
CREATE INDEX idx_next_run ON t_knowledge_document_schedule (next_run_time);
CREATE INDEX idx_lock_until ON t_knowledge_document_schedule (lock_until);
COMMENT ON TABLE t_knowledge_document_schedule IS '鐭ヨ瘑搴撴枃妗ｅ畾鏃跺埛鏂颁换鍔¤〃';

CREATE TABLE t_knowledge_document_schedule_exec (
    id            VARCHAR(20)       NOT NULL PRIMARY KEY,
    schedule_id   VARCHAR(20)       NOT NULL,
    doc_id        VARCHAR(20)       NOT NULL,
    kb_id         VARCHAR(20)       NOT NULL,
    status        VARCHAR(16)  NOT NULL,
    message       VARCHAR(512),
    start_time    TIMESTAMP,
    end_time      TIMESTAMP,
    file_name     VARCHAR(512),
    file_size     BIGINT,
    content_hash  VARCHAR(128),
    etag          VARCHAR(256),
    last_modified VARCHAR(256),
    create_time   TIMESTAMP  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time   TIMESTAMP  NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_schedule_time ON t_knowledge_document_schedule_exec (schedule_id, start_time);
CREATE INDEX idx_doc_id_exec ON t_knowledge_document_schedule_exec (doc_id);
COMMENT ON TABLE t_knowledge_document_schedule_exec IS '鐭ヨ瘑搴撴枃妗ｅ畾鏃跺埛鏂版墽琛岃褰?;

-- ============================================
-- RAG Intent & Query Tables
-- ============================================

CREATE TABLE t_intent_node (
    id                    VARCHAR(20)       NOT NULL PRIMARY KEY,
    kb_id                 VARCHAR(20),
    intent_code           VARCHAR(64)  NOT NULL,
    name                  VARCHAR(64)  NOT NULL,
    level                 SMALLINT     NOT NULL,
    parent_code           VARCHAR(64),
    description           VARCHAR(512),
    examples              TEXT,
    collection_name       VARCHAR(128),
    top_k                 INTEGER,
    mcp_tool_id           VARCHAR(128),
    kind                  SMALLINT     NOT NULL DEFAULT 0,
    prompt_snippet        TEXT,
    prompt_template       TEXT,
    param_prompt_template TEXT,
    sort_order            INTEGER      NOT NULL DEFAULT 0,
    enabled               SMALLINT     NOT NULL DEFAULT 1,
    create_by             VARCHAR(20),
    update_by             VARCHAR(20),
    create_time           TIMESTAMP  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time           TIMESTAMP  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted               SMALLINT     NOT NULL DEFAULT 0
);
COMMENT ON TABLE t_intent_node IS '鎰忓浘鏍戣妭鐐归厤缃〃';

CREATE TABLE t_query_term_mapping (
    id          VARCHAR(20)       NOT NULL PRIMARY KEY,
    domain      VARCHAR(64),
    source_term VARCHAR(128) NOT NULL,
    target_term VARCHAR(128) NOT NULL,
    match_type  SMALLINT     NOT NULL DEFAULT 1,
    priority    INTEGER      NOT NULL DEFAULT 100,
    enabled     SMALLINT     NOT NULL DEFAULT 1,
    remark      VARCHAR(255),
    create_by   VARCHAR(20),
    update_by   VARCHAR(20),
    create_time TIMESTAMP  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted     SMALLINT     NOT NULL DEFAULT 0
);
CREATE INDEX idx_domain ON t_query_term_mapping (domain);
CREATE INDEX idx_source ON t_query_term_mapping (source_term);
COMMENT ON TABLE t_query_term_mapping IS '鍏抽敭璇嶅綊涓€鍖栨槧灏勮〃';

CREATE TABLE t_rag_trace_run (
    id              VARCHAR(20)           NOT NULL PRIMARY KEY,
    trace_id        VARCHAR(64)      NOT NULL,
    trace_name      VARCHAR(128),
    entry_method    VARCHAR(256),
    conversation_id VARCHAR(20),
    task_id         VARCHAR(20),
    user_id         VARCHAR(20),
    status          VARCHAR(16)      NOT NULL DEFAULT 'RUNNING',
    error_message   VARCHAR(1000),
    start_time      TIMESTAMP(3),
    end_time        TIMESTAMP(3),
    duration_ms     BIGINT,
    extra_data      TEXT,
    create_time     TIMESTAMP      DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMP      DEFAULT CURRENT_TIMESTAMP,
    deleted         SMALLINT         DEFAULT 0,
    CONSTRAINT uk_run_id UNIQUE (trace_id)
);
CREATE INDEX idx_task_id ON t_rag_trace_run (task_id);
CREATE INDEX idx_user_id_trace ON t_rag_trace_run (user_id);
COMMENT ON TABLE t_rag_trace_run IS 'Trace 杩愯璁板綍琛?;

CREATE TABLE t_rag_trace_node (
    id             VARCHAR(20)           NOT NULL PRIMARY KEY,
    trace_id       VARCHAR(20)      NOT NULL,
    node_id        VARCHAR(20)      NOT NULL,
    parent_node_id VARCHAR(20),
    depth          INTEGER          DEFAULT 0,
    node_type      VARCHAR(16),
    node_name      VARCHAR(128),
    class_name     VARCHAR(256),
    method_name    VARCHAR(128),
    status         VARCHAR(16)      NOT NULL DEFAULT 'RUNNING',
    error_message  VARCHAR(1000),
    start_time     TIMESTAMP(3),
    end_time       TIMESTAMP(3),
    duration_ms    BIGINT,
    extra_data     TEXT,
    create_time    TIMESTAMP      DEFAULT CURRENT_TIMESTAMP,
    update_time    TIMESTAMP      DEFAULT CURRENT_TIMESTAMP,
    deleted        SMALLINT         DEFAULT 0,
    CONSTRAINT uk_run_node UNIQUE (trace_id, node_id)
);
COMMENT ON TABLE t_rag_trace_node IS 'Trace 鑺傜偣璁板綍琛?;

-- ============================================
-- Ingestion Pipeline Tables
-- ============================================

CREATE TABLE t_ingestion_pipeline (
    id          VARCHAR(20)      NOT NULL PRIMARY KEY,
    name        VARCHAR(100) NOT NULL,
    description TEXT,
    created_by  VARCHAR(20) DEFAULT '',
    updated_by  VARCHAR(20) DEFAULT '',
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted     SMALLINT    NOT NULL DEFAULT 0,
    CONSTRAINT uk_ingestion_pipeline_name UNIQUE (name, deleted)
);
COMMENT ON TABLE t_ingestion_pipeline IS '鎽勫彇娴佹按绾胯〃';

CREATE TABLE t_ingestion_pipeline_node (
    id             VARCHAR(20)      NOT NULL PRIMARY KEY,
    pipeline_id    VARCHAR(20)      NOT NULL,
    node_id        VARCHAR(20) NOT NULL,
    node_type      VARCHAR(16) NOT NULL,
    next_node_id   VARCHAR(20),
    settings_json  JSONB,
    condition_json JSONB,
    created_by     VARCHAR(20) DEFAULT '',
    updated_by     VARCHAR(20) DEFAULT '',
    create_time    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted        SMALLINT    NOT NULL DEFAULT 0,
    CONSTRAINT uk_ingestion_pipeline_node UNIQUE (pipeline_id, node_id, deleted)
);
CREATE INDEX idx_ingestion_pipeline_node_pipeline ON t_ingestion_pipeline_node (pipeline_id);
COMMENT ON TABLE t_ingestion_pipeline_node IS '鎽勫彇娴佹按绾胯妭鐐硅〃';

CREATE TABLE t_ingestion_task (
    id               VARCHAR(20)      NOT NULL PRIMARY KEY,
    pipeline_id      VARCHAR(20)      NOT NULL,
    source_type      VARCHAR(20) NOT NULL,
    source_location  TEXT,
    source_file_name VARCHAR(255),
    status           VARCHAR(16) NOT NULL,
    chunk_count      INTEGER     DEFAULT 0,
    error_message    TEXT,
    logs_json        JSONB,
    metadata_json    JSONB,
    started_at       TIMESTAMP,
    completed_at     TIMESTAMP,
    created_by       VARCHAR(20) DEFAULT '',
    updated_by       VARCHAR(20) DEFAULT '',
    create_time      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted          SMALLINT    NOT NULL DEFAULT 0
);
CREATE INDEX idx_ingestion_task_pipeline ON t_ingestion_task (pipeline_id);
CREATE INDEX idx_ingestion_task_status ON t_ingestion_task (status);
COMMENT ON TABLE t_ingestion_task IS '鎽勫彇浠诲姟琛?;

CREATE TABLE t_ingestion_task_node (
    id            VARCHAR(20)      NOT NULL PRIMARY KEY,
    task_id       VARCHAR(20)      NOT NULL,
    pipeline_id   VARCHAR(20)      NOT NULL,
    node_id       VARCHAR(20) NOT NULL,
    node_type     VARCHAR(16) NOT NULL,
    node_order    INTEGER     NOT NULL DEFAULT 0,
    status        VARCHAR(16) NOT NULL,
    duration_ms   BIGINT      NOT NULL DEFAULT 0,
    message       TEXT,
    error_message TEXT,
    output_json   TEXT,
    create_time   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted       SMALLINT    NOT NULL DEFAULT 0
);
CREATE INDEX idx_ingestion_task_node_task ON t_ingestion_task_node (task_id);
CREATE INDEX idx_ingestion_task_node_pipeline ON t_ingestion_task_node (pipeline_id);
CREATE INDEX idx_ingestion_task_node_status ON t_ingestion_task_node (status);
COMMENT ON TABLE t_ingestion_task_node IS '鎽勫彇浠诲姟鑺傜偣琛?;

-- ============================================
-- Vector Storage Table (pgvector)
-- ============================================

CREATE TABLE t_knowledge_vector (
    id          VARCHAR(20) PRIMARY KEY,
    content     TEXT,
    metadata    JSONB,
    embedding   vector(1536)
);

CREATE INDEX idx_kv_metadata ON t_knowledge_vector USING gin(metadata);
CREATE INDEX idx_kv_embedding ON t_knowledge_vector USING hnsw (embedding vector_cosine_ops);
COMMENT ON TABLE t_knowledge_vector IS '鐭ヨ瘑搴撳悜閲忓瓨鍌ㄨ〃';
COMMENT ON COLUMN t_knowledge_vector.id IS '鍒嗗潡ID';
COMMENT ON COLUMN t_knowledge_vector.content IS '鍒嗗潡鏂囨湰鍐呭';
COMMENT ON COLUMN t_knowledge_vector.metadata IS '鍏冩暟鎹?;
COMMENT ON COLUMN t_knowledge_vector.embedding IS '鍚戦噺';

-- ============================================
-- Column Comments
-- ============================================

-- t_conversation_summary
COMMENT ON COLUMN t_conversation_summary.id IS '涓婚敭ID';
COMMENT ON COLUMN t_conversation_summary.conversation_id IS '浼氳瘽ID';
COMMENT ON COLUMN t_conversation_summary.user_id IS '鐢ㄦ埛ID';
COMMENT ON COLUMN t_conversation_summary.last_message_id IS '鎽樿鏈€鍚庢秷鎭疘D';
COMMENT ON COLUMN t_conversation_summary.content IS '浼氳瘽鎽樿鍐呭';
COMMENT ON COLUMN t_conversation_summary.create_time IS '鍒涘缓鏃堕棿';
COMMENT ON COLUMN t_conversation_summary.update_time IS '鏇存柊鏃堕棿';
COMMENT ON COLUMN t_conversation_summary.deleted IS '鏄惁鍒犻櫎 0锛氭甯?1锛氬垹闄?;

-- t_message
COMMENT ON COLUMN t_message.id IS '涓婚敭ID';
COMMENT ON COLUMN t_message.conversation_id IS '浼氳瘽ID';
COMMENT ON COLUMN t_message.user_id IS '鐢ㄦ埛ID';
COMMENT ON COLUMN t_message.role IS '瑙掕壊锛歶ser/assistant';
COMMENT ON COLUMN t_message.content IS '娑堟伅鍐呭';
COMMENT ON COLUMN t_message.thinking_content IS '娣卞害鎬濊€冨唴瀹?;
COMMENT ON COLUMN t_message.thinking_duration IS '娣卞害鎬濊€冭€楁椂锛堢锛?;
COMMENT ON COLUMN t_message.create_time IS '鍒涘缓鏃堕棿';
COMMENT ON COLUMN t_message.update_time IS '鏇存柊鏃堕棿';
COMMENT ON COLUMN t_message.deleted IS '鏄惁鍒犻櫎 0锛氭甯?1锛氬垹闄?;

-- t_message_feedback
COMMENT ON COLUMN t_message_feedback.id IS '涓婚敭ID';
COMMENT ON COLUMN t_message_feedback.message_id IS '娑堟伅ID';
COMMENT ON COLUMN t_message_feedback.conversation_id IS '浼氳瘽ID';
COMMENT ON COLUMN t_message_feedback.user_id IS '鐢ㄦ埛ID';
COMMENT ON COLUMN t_message_feedback.vote IS '鎶曠エ 1锛氳禐 -1锛氳俯';
COMMENT ON COLUMN t_message_feedback.reason IS '鍙嶉鍘熷洜';
COMMENT ON COLUMN t_message_feedback.comment IS '鍙嶉璇勮';
COMMENT ON COLUMN t_message_feedback.create_time IS '鍒涘缓鏃堕棿';
COMMENT ON COLUMN t_message_feedback.update_time IS '鏇存柊鏃堕棿';
COMMENT ON COLUMN t_message_feedback.deleted IS '鏄惁鍒犻櫎 0锛氭甯?1锛氬垹闄?;

-- t_sample_question
COMMENT ON COLUMN t_sample_question.id IS 'ID';
COMMENT ON COLUMN t_sample_question.title IS '灞曠ず鏍囬';
COMMENT ON COLUMN t_sample_question.description IS '鎻忚堪鎴栨彁绀?;
COMMENT ON COLUMN t_sample_question.question IS '绀轰緥闂鍐呭';
COMMENT ON COLUMN t_sample_question.create_time IS '鍒涘缓鏃堕棿';
COMMENT ON COLUMN t_sample_question.update_time IS '鏇存柊鏃堕棿';
COMMENT ON COLUMN t_sample_question.deleted IS '鏄惁鍒犻櫎 0锛氭甯?1锛氬垹闄?;

-- t_knowledge_base
COMMENT ON COLUMN t_knowledge_base.id IS '涓婚敭 ID';
COMMENT ON COLUMN t_knowledge_base.name IS '鐭ヨ瘑搴撳悕绉?;
COMMENT ON COLUMN t_knowledge_base.embedding_model IS '宓屽叆妯″瀷鏍囪瘑';
COMMENT ON COLUMN t_knowledge_base.collection_name IS 'Collection鍚嶇О';
COMMENT ON COLUMN t_knowledge_base.created_by IS '鍒涘缓浜?;
COMMENT ON COLUMN t_knowledge_base.updated_by IS '淇敼浜?;
COMMENT ON COLUMN t_knowledge_base.create_time IS '鍒涘缓鏃堕棿';
COMMENT ON COLUMN t_knowledge_base.update_time IS '鏇存柊鏃堕棿';
COMMENT ON COLUMN t_knowledge_base.deleted IS '鏄惁鍒犻櫎 0锛氭甯?1锛氬垹闄?;

-- t_knowledge_document
COMMENT ON COLUMN t_knowledge_document.id IS 'ID';
COMMENT ON COLUMN t_knowledge_document.kb_id IS '鐭ヨ瘑搴揑D';
COMMENT ON COLUMN t_knowledge_document.doc_name IS '鏂囨。鍚嶇О';
COMMENT ON COLUMN t_knowledge_document.enabled IS '鏄惁鍚敤 1锛氬惎鐢?0锛氱鐢?;
COMMENT ON COLUMN t_knowledge_document.chunk_count IS '鍒嗗潡鏁伴噺';
COMMENT ON COLUMN t_knowledge_document.file_url IS '鏂囦欢瀛樺偍璺緞';
COMMENT ON COLUMN t_knowledge_document.file_type IS '鏂囦欢绫诲瀷';
COMMENT ON COLUMN t_knowledge_document.file_size IS '鏂囦欢澶у皬锛堝瓧鑺傦級';
COMMENT ON COLUMN t_knowledge_document.process_mode IS '澶勭悊妯″紡锛歝hunk/pipeline';
COMMENT ON COLUMN t_knowledge_document.status IS '鐘舵€侊細pending/running/success/failed';
COMMENT ON COLUMN t_knowledge_document.source_type IS '鏉ユ簮绫诲瀷锛歠ile/url';
COMMENT ON COLUMN t_knowledge_document.source_location IS '鏉ユ簮鍦板潃';
COMMENT ON COLUMN t_knowledge_document.schedule_enabled IS '鏄惁鍚敤瀹氭椂鍒锋柊';
COMMENT ON COLUMN t_knowledge_document.schedule_cron IS '瀹氭椂琛ㄨ揪寮?;
COMMENT ON COLUMN t_knowledge_document.chunk_strategy IS '鍒嗗潡绛栫暐';
COMMENT ON COLUMN t_knowledge_document.chunk_config IS '鍒嗗潡閰嶇疆JSON';
COMMENT ON COLUMN t_knowledge_document.pipeline_id IS 'Pipeline ID';
COMMENT ON COLUMN t_knowledge_document.created_by IS '鍒涘缓浜?;
COMMENT ON COLUMN t_knowledge_document.updated_by IS '淇敼浜?;
COMMENT ON COLUMN t_knowledge_document.create_time IS '鍒涘缓鏃堕棿';
COMMENT ON COLUMN t_knowledge_document.update_time IS '鏇存柊鏃堕棿';
COMMENT ON COLUMN t_knowledge_document.deleted IS '鏄惁鍒犻櫎 0锛氭甯?1锛氬垹闄?;

-- t_knowledge_chunk
COMMENT ON COLUMN t_knowledge_chunk.id IS 'ID';
COMMENT ON COLUMN t_knowledge_chunk.kb_id IS '鐭ヨ瘑搴揑D';
COMMENT ON COLUMN t_knowledge_chunk.doc_id IS '鏂囨。ID';
COMMENT ON COLUMN t_knowledge_chunk.chunk_index IS '鍒嗗潡搴忓彿';
COMMENT ON COLUMN t_knowledge_chunk.content IS '鍒嗗潡鍐呭';
COMMENT ON COLUMN t_knowledge_chunk.content_hash IS '鍐呭鍝堝笇';
COMMENT ON COLUMN t_knowledge_chunk.char_count IS '瀛楃鏁?;
COMMENT ON COLUMN t_knowledge_chunk.token_count IS 'Token鏁?;
COMMENT ON COLUMN t_knowledge_chunk.enabled IS '鏄惁鍚敤';
COMMENT ON COLUMN t_knowledge_chunk.created_by IS '鍒涘缓浜?;
COMMENT ON COLUMN t_knowledge_chunk.updated_by IS '淇敼浜?;
COMMENT ON COLUMN t_knowledge_chunk.create_time IS '鍒涘缓鏃堕棿';
COMMENT ON COLUMN t_knowledge_chunk.update_time IS '鏇存柊鏃堕棿';
COMMENT ON COLUMN t_knowledge_chunk.deleted IS '鏄惁鍒犻櫎 0锛氭甯?1锛氬垹闄?;

-- t_knowledge_document_chunk_log
COMMENT ON COLUMN t_knowledge_document_chunk_log.id IS 'ID';
COMMENT ON COLUMN t_knowledge_document_chunk_log.doc_id IS '鏂囨。ID';
COMMENT ON COLUMN t_knowledge_document_chunk_log.status IS '鐘舵€?;
COMMENT ON COLUMN t_knowledge_document_chunk_log.process_mode IS '澶勭悊妯″紡';
COMMENT ON COLUMN t_knowledge_document_chunk_log.chunk_strategy IS '鍒嗗潡绛栫暐';
COMMENT ON COLUMN t_knowledge_document_chunk_log.pipeline_id IS 'Pipeline ID';
COMMENT ON COLUMN t_knowledge_document_chunk_log.extract_duration IS '鎻愬彇鑰楁椂锛堟绉掞級';
COMMENT ON COLUMN t_knowledge_document_chunk_log.chunk_duration IS '鍒嗗潡鑰楁椂锛堟绉掞級';
COMMENT ON COLUMN t_knowledge_document_chunk_log.embed_duration IS '鍚戦噺鍖栬€楁椂锛堟绉掞級';
COMMENT ON COLUMN t_knowledge_document_chunk_log.persist_duration IS 'DB鎸佷箙鍖栬€楁椂锛堟绉掞級';
COMMENT ON COLUMN t_knowledge_document_chunk_log.total_duration IS '鎬昏€楁椂锛堟绉掞級';
COMMENT ON COLUMN t_knowledge_document_chunk_log.chunk_count IS '鍒嗗潡鏁伴噺';
COMMENT ON COLUMN t_knowledge_document_chunk_log.error_message IS '閿欒淇℃伅';
COMMENT ON COLUMN t_knowledge_document_chunk_log.start_time IS '寮€濮嬫椂闂?;
COMMENT ON COLUMN t_knowledge_document_chunk_log.end_time IS '缁撴潫鏃堕棿';
COMMENT ON COLUMN t_knowledge_document_chunk_log.create_time IS '鍒涘缓鏃堕棿';
COMMENT ON COLUMN t_knowledge_document_chunk_log.update_time IS '鏇存柊鏃堕棿';

-- t_knowledge_document_schedule
COMMENT ON COLUMN t_knowledge_document_schedule.id IS 'ID';
COMMENT ON COLUMN t_knowledge_document_schedule.doc_id IS '鏂囨。ID';
COMMENT ON COLUMN t_knowledge_document_schedule.kb_id IS '鐭ヨ瘑搴揑D';
COMMENT ON COLUMN t_knowledge_document_schedule.cron_expr IS 'Cron琛ㄨ揪寮?;
COMMENT ON COLUMN t_knowledge_document_schedule.enabled IS '鏄惁鍚敤';
COMMENT ON COLUMN t_knowledge_document_schedule.next_run_time IS '涓嬫鎵ц鏃堕棿';
COMMENT ON COLUMN t_knowledge_document_schedule.last_run_time IS '涓婃鎵ц鏃堕棿';
COMMENT ON COLUMN t_knowledge_document_schedule.last_success_time IS '涓婃鎴愬姛鏃堕棿';
COMMENT ON COLUMN t_knowledge_document_schedule.last_status IS '涓婃鐘舵€?;
COMMENT ON COLUMN t_knowledge_document_schedule.last_error IS '涓婃閿欒';
COMMENT ON COLUMN t_knowledge_document_schedule.last_etag IS '涓婃ETag';
COMMENT ON COLUMN t_knowledge_document_schedule.last_modified IS '涓婃淇敼鏃堕棿';
COMMENT ON COLUMN t_knowledge_document_schedule.last_content_hash IS '涓婃鍐呭鍝堝笇';
COMMENT ON COLUMN t_knowledge_document_schedule.lock_owner IS '閿佹寔鏈夎€?;
COMMENT ON COLUMN t_knowledge_document_schedule.lock_until IS '閿佽繃鏈熸椂闂?;
COMMENT ON COLUMN t_knowledge_document_schedule.create_time IS '鍒涘缓鏃堕棿';
COMMENT ON COLUMN t_knowledge_document_schedule.update_time IS '鏇存柊鏃堕棿';

-- t_knowledge_document_schedule_exec
COMMENT ON COLUMN t_knowledge_document_schedule_exec.id IS 'ID';
COMMENT ON COLUMN t_knowledge_document_schedule_exec.schedule_id IS '璋冨害ID';
COMMENT ON COLUMN t_knowledge_document_schedule_exec.doc_id IS '鏂囨。ID';
COMMENT ON COLUMN t_knowledge_document_schedule_exec.kb_id IS '鐭ヨ瘑搴揑D';
COMMENT ON COLUMN t_knowledge_document_schedule_exec.status IS '鐘舵€?;
COMMENT ON COLUMN t_knowledge_document_schedule_exec.message IS '娑堟伅';
COMMENT ON COLUMN t_knowledge_document_schedule_exec.start_time IS '寮€濮嬫椂闂?;
COMMENT ON COLUMN t_knowledge_document_schedule_exec.end_time IS '缁撴潫鏃堕棿';
COMMENT ON COLUMN t_knowledge_document_schedule_exec.file_name IS '鏂囦欢鍚?;
COMMENT ON COLUMN t_knowledge_document_schedule_exec.file_size IS '鏂囦欢澶у皬';
COMMENT ON COLUMN t_knowledge_document_schedule_exec.content_hash IS '鍐呭鍝堝笇';
COMMENT ON COLUMN t_knowledge_document_schedule_exec.etag IS 'ETag';
COMMENT ON COLUMN t_knowledge_document_schedule_exec.last_modified IS '鏈€鍚庝慨鏀规椂闂?;
COMMENT ON COLUMN t_knowledge_document_schedule_exec.create_time IS '鍒涘缓鏃堕棿';
COMMENT ON COLUMN t_knowledge_document_schedule_exec.update_time IS '鏇存柊鏃堕棿';

-- t_intent_node
COMMENT ON COLUMN t_intent_node.id IS '鑷涓婚敭';
COMMENT ON COLUMN t_intent_node.kb_id IS '鐭ヨ瘑搴揑D';
COMMENT ON COLUMN t_intent_node.intent_code IS '涓氬姟鍞竴鏍囪瘑';
COMMENT ON COLUMN t_intent_node.name IS '灞曠ず鍚嶇О';
COMMENT ON COLUMN t_intent_node.level IS '灞傜骇 0锛欴OMAIN 1锛欳ATEGORY 2锛歍OPIC';
COMMENT ON COLUMN t_intent_node.parent_code IS '鐖惰妭鐐规爣璇?;
COMMENT ON COLUMN t_intent_node.description IS '璇箟鎻忚堪';
COMMENT ON COLUMN t_intent_node.examples IS '绀轰緥闂';
COMMENT ON COLUMN t_intent_node.collection_name IS '鍏宠仈鐨凜ollection鍚嶇О';
COMMENT ON COLUMN t_intent_node.top_k IS '鐭ヨ瘑搴撴绱opK';
COMMENT ON COLUMN t_intent_node.mcp_tool_id IS 'MCP宸ュ叿ID';
COMMENT ON COLUMN t_intent_node.kind IS '绫诲瀷 0锛歊AG鐭ヨ瘑搴撶被 1锛歋YSTEM绯荤粺浜や簰绫?;
COMMENT ON COLUMN t_intent_node.prompt_snippet IS '鎻愮ず璇嶇墖娈?;
COMMENT ON COLUMN t_intent_node.prompt_template IS '鎻愮ず璇嶆ā鏉?;
COMMENT ON COLUMN t_intent_node.param_prompt_template IS '鍙傛暟鎻愬彇鎻愮ず璇嶆ā鏉匡紙MCP妯″紡涓撳睘锛?;
COMMENT ON COLUMN t_intent_node.sort_order IS '鎺掑簭瀛楁';
COMMENT ON COLUMN t_intent_node.enabled IS '鏄惁鍚敤 1锛氬惎鐢?0锛氱鐢?;
COMMENT ON COLUMN t_intent_node.create_by IS '鍒涘缓浜?;
COMMENT ON COLUMN t_intent_node.update_by IS '淇敼浜?;
COMMENT ON COLUMN t_intent_node.create_time IS '鍒涘缓鏃堕棿';
COMMENT ON COLUMN t_intent_node.update_time IS '淇敼鏃堕棿';
COMMENT ON COLUMN t_intent_node.deleted IS '鏄惁鍒犻櫎 0锛氭甯?1锛氬垹闄?;

-- t_query_term_mapping
COMMENT ON COLUMN t_query_term_mapping.id IS 'ID';
COMMENT ON COLUMN t_query_term_mapping.domain IS '棰嗗煙';
COMMENT ON COLUMN t_query_term_mapping.source_term IS '婧愯瘝';
COMMENT ON COLUMN t_query_term_mapping.target_term IS '鐩爣璇?;
COMMENT ON COLUMN t_query_term_mapping.match_type IS '鍖归厤绫诲瀷 1锛氱簿纭?2锛氭ā绯?;
COMMENT ON COLUMN t_query_term_mapping.priority IS '浼樺厛绾?;
COMMENT ON COLUMN t_query_term_mapping.enabled IS '鏄惁鍚敤';
COMMENT ON COLUMN t_query_term_mapping.remark IS '澶囨敞';
COMMENT ON COLUMN t_query_term_mapping.create_by IS '鍒涘缓浜?;
COMMENT ON COLUMN t_query_term_mapping.update_by IS '淇敼浜?;
COMMENT ON COLUMN t_query_term_mapping.create_time IS '鍒涘缓鏃堕棿';
COMMENT ON COLUMN t_query_term_mapping.update_time IS '淇敼鏃堕棿';
COMMENT ON COLUMN t_query_term_mapping.deleted IS '鏄惁鍒犻櫎 0锛氭甯?1锛氬垹闄?;

-- t_rag_trace_run
COMMENT ON COLUMN t_rag_trace_run.id IS 'ID';
COMMENT ON COLUMN t_rag_trace_run.trace_id IS '鍏ㄥ眬閾捐矾ID';
COMMENT ON COLUMN t_rag_trace_run.trace_name IS '閾捐矾鍚嶇О';
COMMENT ON COLUMN t_rag_trace_run.entry_method IS '鍏ュ彛鏂规硶';
COMMENT ON COLUMN t_rag_trace_run.conversation_id IS '浼氳瘽ID';
COMMENT ON COLUMN t_rag_trace_run.task_id IS '浠诲姟ID';
COMMENT ON COLUMN t_rag_trace_run.user_id IS '鐢ㄦ埛ID';
COMMENT ON COLUMN t_rag_trace_run.status IS 'RUNNING/SUCCESS/ERROR';
COMMENT ON COLUMN t_rag_trace_run.error_message IS '閿欒淇℃伅';
COMMENT ON COLUMN t_rag_trace_run.start_time IS '寮€濮嬫椂闂?;
COMMENT ON COLUMN t_rag_trace_run.end_time IS '缁撴潫鏃堕棿';
COMMENT ON COLUMN t_rag_trace_run.duration_ms IS '鑰楁椂姣';
COMMENT ON COLUMN t_rag_trace_run.extra_data IS '鎵╁睍瀛楁(JSON)';
COMMENT ON COLUMN t_rag_trace_run.create_time IS '鍒涘缓鏃堕棿';
COMMENT ON COLUMN t_rag_trace_run.update_time IS '鏇存柊鏃堕棿';
COMMENT ON COLUMN t_rag_trace_run.deleted IS '鏄惁鍒犻櫎';

-- t_rag_trace_node
COMMENT ON COLUMN t_rag_trace_node.id IS 'ID';
COMMENT ON COLUMN t_rag_trace_node.trace_id IS '鎵€灞為摼璺疘D';
COMMENT ON COLUMN t_rag_trace_node.node_id IS '鑺傜偣ID';
COMMENT ON COLUMN t_rag_trace_node.parent_node_id IS '鐖惰妭鐐笽D';
COMMENT ON COLUMN t_rag_trace_node.depth IS '鑺傜偣娣卞害';
COMMENT ON COLUMN t_rag_trace_node.node_type IS '鑺傜偣绫诲瀷';
COMMENT ON COLUMN t_rag_trace_node.node_name IS '鑺傜偣鍚嶇О';
COMMENT ON COLUMN t_rag_trace_node.class_name IS '绫诲悕';
COMMENT ON COLUMN t_rag_trace_node.method_name IS '鏂规硶鍚?;
COMMENT ON COLUMN t_rag_trace_node.status IS 'RUNNING/SUCCESS/ERROR';
COMMENT ON COLUMN t_rag_trace_node.error_message IS '閿欒淇℃伅';
COMMENT ON COLUMN t_rag_trace_node.start_time IS '寮€濮嬫椂闂?;
COMMENT ON COLUMN t_rag_trace_node.end_time IS '缁撴潫鏃堕棿';
COMMENT ON COLUMN t_rag_trace_node.duration_ms IS '鑰楁椂姣';
COMMENT ON COLUMN t_rag_trace_node.extra_data IS '鎵╁睍瀛楁(JSON)';
COMMENT ON COLUMN t_rag_trace_node.create_time IS '鍒涘缓鏃堕棿';
COMMENT ON COLUMN t_rag_trace_node.update_time IS '鏇存柊鏃堕棿';
COMMENT ON COLUMN t_rag_trace_node.deleted IS '鏄惁鍒犻櫎';

-- t_ingestion_pipeline
COMMENT ON COLUMN t_ingestion_pipeline.id IS 'ID';
COMMENT ON COLUMN t_ingestion_pipeline.name IS '娴佹按绾垮悕绉?;
COMMENT ON COLUMN t_ingestion_pipeline.description IS '娴佹按绾挎弿杩?;
COMMENT ON COLUMN t_ingestion_pipeline.created_by IS '鍒涘缓浜?;
COMMENT ON COLUMN t_ingestion_pipeline.updated_by IS '鏇存柊浜?;
COMMENT ON COLUMN t_ingestion_pipeline.create_time IS '鍒涘缓鏃堕棿';
COMMENT ON COLUMN t_ingestion_pipeline.update_time IS '鏇存柊鏃堕棿';
COMMENT ON COLUMN t_ingestion_pipeline.deleted IS '鏄惁鍒犻櫎 0锛氭甯?1锛氬垹闄?;

-- t_ingestion_pipeline_node
COMMENT ON COLUMN t_ingestion_pipeline_node.id IS 'ID';
COMMENT ON COLUMN t_ingestion_pipeline_node.pipeline_id IS '娴佹按绾縄D';
COMMENT ON COLUMN t_ingestion_pipeline_node.node_id IS '鑺傜偣鏍囪瘑(鍚屼竴娴佹按绾垮唴鍞竴)';
COMMENT ON COLUMN t_ingestion_pipeline_node.node_type IS '鑺傜偣绫诲瀷';
COMMENT ON COLUMN t_ingestion_pipeline_node.next_node_id IS '涓嬩竴涓妭鐐笽D';
COMMENT ON COLUMN t_ingestion_pipeline_node.settings_json IS '鑺傜偣閰嶇疆JSON';
COMMENT ON COLUMN t_ingestion_pipeline_node.condition_json IS '鏉′欢JSON';
COMMENT ON COLUMN t_ingestion_pipeline_node.created_by IS '鍒涘缓浜?;
COMMENT ON COLUMN t_ingestion_pipeline_node.updated_by IS '鏇存柊浜?;
COMMENT ON COLUMN t_ingestion_pipeline_node.create_time IS '鍒涘缓鏃堕棿';
COMMENT ON COLUMN t_ingestion_pipeline_node.update_time IS '鏇存柊鏃堕棿';
COMMENT ON COLUMN t_ingestion_pipeline_node.deleted IS '鏄惁鍒犻櫎 0锛氭甯?1锛氬垹闄?;

-- t_ingestion_task
COMMENT ON COLUMN t_ingestion_task.id IS 'ID';
COMMENT ON COLUMN t_ingestion_task.pipeline_id IS '娴佹按绾縄D';
COMMENT ON COLUMN t_ingestion_task.source_type IS '鏉ユ簮绫诲瀷';
COMMENT ON COLUMN t_ingestion_task.source_location IS '鏉ユ簮鍦板潃鎴朥RL';
COMMENT ON COLUMN t_ingestion_task.source_file_name IS '鍘熷鏂囦欢鍚?;
COMMENT ON COLUMN t_ingestion_task.status IS '浠诲姟鐘舵€?;
COMMENT ON COLUMN t_ingestion_task.chunk_count IS '鍒嗗潡鏁伴噺';
COMMENT ON COLUMN t_ingestion_task.error_message IS '閿欒淇℃伅';
COMMENT ON COLUMN t_ingestion_task.logs_json IS '鑺傜偣鏃ュ織JSON';
COMMENT ON COLUMN t_ingestion_task.metadata_json IS '鎵╁睍鍏冩暟鎹甁SON';
COMMENT ON COLUMN t_ingestion_task.started_at IS '寮€濮嬫椂闂?;
COMMENT ON COLUMN t_ingestion_task.completed_at IS '瀹屾垚鏃堕棿';
COMMENT ON COLUMN t_ingestion_task.created_by IS '鍒涘缓浜?;
COMMENT ON COLUMN t_ingestion_task.updated_by IS '鏇存柊浜?;
COMMENT ON COLUMN t_ingestion_task.create_time IS '鍒涘缓鏃堕棿';
COMMENT ON COLUMN t_ingestion_task.update_time IS '鏇存柊鏃堕棿';
COMMENT ON COLUMN t_ingestion_task.deleted IS '鏄惁鍒犻櫎 0锛氭甯?1锛氬垹闄?;

-- t_ingestion_task_node
COMMENT ON COLUMN t_ingestion_task_node.id IS 'ID';
COMMENT ON COLUMN t_ingestion_task_node.task_id IS '浠诲姟ID';
COMMENT ON COLUMN t_ingestion_task_node.pipeline_id IS '娴佹按绾縄D';
COMMENT ON COLUMN t_ingestion_task_node.node_id IS '鑺傜偣鏍囪瘑';
COMMENT ON COLUMN t_ingestion_task_node.node_type IS '鑺傜偣绫诲瀷';
COMMENT ON COLUMN t_ingestion_task_node.node_order IS '鑺傜偣椤哄簭';
COMMENT ON COLUMN t_ingestion_task_node.status IS '鑺傜偣鐘舵€?;
COMMENT ON COLUMN t_ingestion_task_node.duration_ms IS '鎵ц鑰楁椂(姣)';
COMMENT ON COLUMN t_ingestion_task_node.message IS '鑺傜偣娑堟伅';
COMMENT ON COLUMN t_ingestion_task_node.error_message IS '閿欒淇℃伅';
COMMENT ON COLUMN t_ingestion_task_node.output_json IS '鑺傜偣杈撳嚭JSON(鍏ㄩ噺)';
COMMENT ON COLUMN t_ingestion_task_node.create_time IS '鍒涘缓鏃堕棿';
COMMENT ON COLUMN t_ingestion_task_node.update_time IS '鏇存柊鏃堕棿';
COMMENT ON COLUMN t_ingestion_task_node.deleted IS '鏄惁鍒犻櫎 0锛氭甯?1锛氬垹闄?;

-- ============================================
-- Memory & Messaging Tables
-- ============================================

CREATE TABLE t_outbox_event (
    id VARCHAR(20) PRIMARY KEY,
    topic VARCHAR(128) NOT NULL,
    message_key VARCHAR(128) NOT NULL,
    event_type VARCHAR(128) NOT NULL,
    payload_json JSONB NOT NULL,
    status VARCHAR(16) NOT NULL,
    retry_count INTEGER NOT NULL DEFAULT 0,
    next_retry_time TIMESTAMP,
    last_error TEXT,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted SMALLINT DEFAULT 0
);
CREATE INDEX idx_outbox_status_retry ON t_outbox_event (status, next_retry_time, create_time);

CREATE TABLE t_short_term_memory (
    id VARCHAR(20) PRIMARY KEY,
    user_id VARCHAR(20) NOT NULL,
    conversation_id VARCHAR(20),
    memory_type VARCHAR(32) NOT NULL,
    content TEXT NOT NULL,
    metadata_json JSONB,
    source_message_ids JSONB,
    importance_score NUMERIC(4, 3) DEFAULT 0,
    access_count INTEGER DEFAULT 0,
    last_access_time TIMESTAMP,
    decay_score NUMERIC(4, 3) DEFAULT 0,
    expires_time TIMESTAMP,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted SMALLINT DEFAULT 0
);
CREATE INDEX idx_stm_user_conv_time ON t_short_term_memory (user_id, conversation_id, create_time DESC);
CREATE INDEX idx_stm_user_type_decay ON t_short_term_memory (user_id, memory_type, decay_score DESC);
CREATE INDEX idx_stm_metadata_gin ON t_short_term_memory USING GIN (metadata_json);

CREATE TABLE t_long_term_memory (
    id VARCHAR(20) PRIMARY KEY,
    user_id VARCHAR(20) NOT NULL,
    memory_category VARCHAR(32) NOT NULL,
    title VARCHAR(256),
    content TEXT NOT NULL,
    source_type VARCHAR(32) NOT NULL,
    source_ids JSONB,
    tags JSONB,
    importance_score NUMERIC(4, 3) DEFAULT 0,
    confidence_level NUMERIC(4, 3) DEFAULT 0,
    embedding_model VARCHAR(64),
    vector_ref_id VARCHAR(64),
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted SMALLINT DEFAULT 0
);
CREATE INDEX idx_ltm_user_category_importance ON t_long_term_memory (user_id, memory_category, importance_score DESC);
CREATE INDEX idx_ltm_tags_gin ON t_long_term_memory USING GIN (tags);

CREATE TABLE t_semantic_memory (
    id VARCHAR(20) PRIMARY KEY,
    user_id VARCHAR(20) NOT NULL,
    semantic_key VARCHAR(64) NOT NULL,
    semantic_type VARCHAR(32) NOT NULL,
    value_json JSONB NOT NULL,
    confidence_level NUMERIC(4, 3) DEFAULT 0,
    source_memory_ids JSONB,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted SMALLINT DEFAULT 0,
    CONSTRAINT uk_semantic_memory UNIQUE (user_id, semantic_key, semantic_type)
);
CREATE INDEX idx_sem_user_type ON t_semantic_memory (user_id, semantic_type);

CREATE TABLE t_memory_conflict_log (
    id VARCHAR(20) PRIMARY KEY,
    user_id VARCHAR(20) NOT NULL,
    memory_id_1 VARCHAR(20) NOT NULL,
    memory_id_2 VARCHAR(20) NOT NULL,
    conflict_type VARCHAR(32) NOT NULL,
    severity VARCHAR(16) NOT NULL,
    resolution_status VARCHAR(16) NOT NULL,
    resolution_action VARCHAR(32),
    resolved_by VARCHAR(32),
    resolved_at TIMESTAMP,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted SMALLINT DEFAULT 0
);
CREATE INDEX idx_memory_conflict_user_status ON t_memory_conflict_log (user_id, resolution_status, create_time DESC);

CREATE TABLE t_long_term_memory_vector (
    id VARCHAR(20) PRIMARY KEY,
    user_id VARCHAR(20) NOT NULL,
    content TEXT NOT NULL,
    embedding vector(1536) NOT NULL,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_ltm_vector_user ON t_long_term_memory_vector (user_id);
CREATE INDEX idx_ltm_vector_hnsw ON t_long_term_memory_vector USING hnsw (embedding vector_cosine_ops);