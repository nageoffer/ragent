-- ragent v1.2 -> v1.3 升级脚本
-- Pulsar Outbox 与四层 Memory 表

CREATE TABLE IF NOT EXISTS t_outbox_event (
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
CREATE INDEX IF NOT EXISTS idx_outbox_status_retry ON t_outbox_event (status, next_retry_time, create_time);

CREATE TABLE IF NOT EXISTS t_short_term_memory (
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
CREATE INDEX IF NOT EXISTS idx_stm_user_conv_time ON t_short_term_memory (user_id, conversation_id, create_time DESC);
CREATE INDEX IF NOT EXISTS idx_stm_user_type_decay ON t_short_term_memory (user_id, memory_type, decay_score DESC);
CREATE INDEX IF NOT EXISTS idx_stm_metadata_gin ON t_short_term_memory USING GIN (metadata_json);

CREATE TABLE IF NOT EXISTS t_long_term_memory (
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
CREATE INDEX IF NOT EXISTS idx_ltm_user_category_importance
ON t_long_term_memory (user_id, memory_category, importance_score DESC);
CREATE INDEX IF NOT EXISTS idx_ltm_tags_gin ON t_long_term_memory USING GIN (tags);

CREATE TABLE IF NOT EXISTS t_semantic_memory (
    id VARCHAR(20) PRIMARY KEY,
    user_id VARCHAR(20) NOT NULL,
    semantic_key VARCHAR(64) NOT NULL,
    semantic_type VARCHAR(32) NOT NULL,
    value_json JSONB NOT NULL,
    confidence_level NUMERIC(4, 3) DEFAULT 0,
    source_memory_ids JSONB,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted SMALLINT DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_semantic_memory
ON t_semantic_memory (user_id, semantic_key, semantic_type);
CREATE INDEX IF NOT EXISTS idx_sem_user_type ON t_semantic_memory (user_id, semantic_type);

CREATE TABLE IF NOT EXISTS t_memory_conflict_log (
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
CREATE INDEX IF NOT EXISTS idx_memory_conflict_user_status
ON t_memory_conflict_log (user_id, resolution_status, create_time DESC);

CREATE TABLE IF NOT EXISTS t_long_term_memory_vector (
    id VARCHAR(20) PRIMARY KEY,
    user_id VARCHAR(20) NOT NULL,
    content TEXT NOT NULL,
    embedding vector(1536) NOT NULL,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_ltm_vector_user ON t_long_term_memory_vector (user_id);
CREATE INDEX IF NOT EXISTS idx_ltm_vector_hnsw ON t_long_term_memory_vector USING hnsw (embedding vector_cosine_ops);
