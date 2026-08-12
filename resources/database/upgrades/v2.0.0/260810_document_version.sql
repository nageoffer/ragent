-- v2.0.0 260810 文档操作统一版本

ALTER TABLE t_knowledge_document ADD COLUMN IF NOT EXISTS document_version VARCHAR(20);
UPDATE t_knowledge_document SET document_version = id::VARCHAR WHERE document_version IS NULL;
ALTER TABLE t_knowledge_document ALTER COLUMN document_version SET NOT NULL;

COMMENT ON COLUMN t_knowledge_document.document_version IS '当前文档操作版本及写入 fencing token';
