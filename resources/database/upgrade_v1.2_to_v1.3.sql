-- ragent v1.2 -> v1.3 升级脚本
-- t_knowledge_vector 表：新增 tsvector 列及全文检索索引，支持混合检索通道

-- 1. 新增 tsvector 列
ALTER TABLE t_knowledge_vector ADD COLUMN IF NOT EXISTS tsv tsvector;

-- 2. GIN 索引加速全文检索
CREATE INDEX IF NOT EXISTS idx_kv_tsv ON t_knowledge_vector USING GIN(tsv);

-- 3. 触发器：自动维护 tsv 列
CREATE OR REPLACE FUNCTION kv_tsv_trigger() RETURNS trigger AS $$
BEGIN
  NEW.tsv := to_tsvector('simple', COALESCE(NEW.content, ''));
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_kv_tsv ON t_knowledge_vector;
CREATE TRIGGER trg_kv_tsv BEFORE INSERT OR UPDATE OF content ON t_knowledge_vector
  FOR EACH ROW EXECUTE FUNCTION kv_tsv_trigger();

-- 4. 回填已有数据
UPDATE t_knowledge_vector SET tsv = to_tsvector('simple', COALESCE(content, ''));
