-- v1.1.0 260730 摄取内核化升级
-- 文档表分块策略与分块配置合并为摄取配置并记录真实 MIME；分块表新增向量文本；分块日志的分块策略改为解析档位
-- 旧 chunk_config 不做兼容读，直接丢弃，存量文档回落系统默认预算

-- 1. 文档表
ALTER TABLE t_knowledge_document ADD COLUMN IF NOT EXISTS mime_type VARCHAR(128);
ALTER TABLE t_knowledge_document ADD COLUMN IF NOT EXISTS ingestion_spec JSONB;
ALTER TABLE t_knowledge_document DROP COLUMN IF EXISTS chunk_strategy;
ALTER TABLE t_knowledge_document DROP COLUMN IF EXISTS chunk_config;

COMMENT ON COLUMN t_knowledge_document.mime_type IS '真实MIME类型';
COMMENT ON COLUMN t_knowledge_document.ingestion_spec IS '文档级摄取配置：解析档位 + 分块预算';

-- 2. 分块表
ALTER TABLE t_knowledge_chunk ADD COLUMN IF NOT EXISTS embedding_text TEXT;

COMMENT ON COLUMN t_knowledge_chunk.embedding_text IS '向量文本';

-- 3. 分块日志表
ALTER TABLE t_knowledge_document_chunk_log ADD COLUMN IF NOT EXISTS parse_profile VARCHAR(16);
ALTER TABLE t_knowledge_document_chunk_log DROP COLUMN IF EXISTS chunk_strategy;

COMMENT ON COLUMN t_knowledge_document_chunk_log.parse_profile IS '解析档位';
