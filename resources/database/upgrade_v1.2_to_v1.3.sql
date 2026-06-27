-- ragent v1.2 -> v1.3 升级脚本
-- t_knowledge_vector 表：新增 tsvector 列及全文检索索引，支持混合检索通道

-- 1. 新增 tsvector 列
ALTER TABLE t_knowledge_vector ADD COLUMN IF NOT EXISTS tsv tsvector;

-- 2. GIN 索引加速全文检索
CREATE INDEX IF NOT EXISTS idx_kv_tsv ON t_knowledge_vector USING GIN(tsv);

-- 3. 启用 zhparser 中文分词扩展
CREATE EXTENSION IF NOT EXISTS zhparser;
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_ts_config WHERE cfgname = 'zhparser') THEN
    CREATE TEXT SEARCH CONFIGURATION zhparser (PARSER = zhparser);
  END IF;
END $$;

-- 3a. token 类型映射：zhparser 产出的 n/v/a/i/e/l 必须映射到 simple 词典，否则 PG 丢弃这些 token
ALTER TEXT SEARCH CONFIGURATION zhparser DROP MAPPING IF EXISTS FOR n;
ALTER TEXT SEARCH CONFIGURATION zhparser DROP MAPPING IF EXISTS FOR v;
ALTER TEXT SEARCH CONFIGURATION zhparser DROP MAPPING IF EXISTS FOR a;
ALTER TEXT SEARCH CONFIGURATION zhparser DROP MAPPING IF EXISTS FOR i;
ALTER TEXT SEARCH CONFIGURATION zhparser DROP MAPPING IF EXISTS FOR e;
ALTER TEXT SEARCH CONFIGURATION zhparser DROP MAPPING IF EXISTS FOR l;
ALTER TEXT SEARCH CONFIGURATION zhparser ADD MAPPING FOR n,v,a,i,e,l WITH simple;

-- 4. 触发器：自动维护 tsv 列（zhparser 分词）
CREATE OR REPLACE FUNCTION kv_tsv_trigger() RETURNS trigger AS $$
BEGIN
  NEW.tsv := to_tsvector('zhparser', COALESCE(NEW.content, ''));
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_kv_tsv ON t_knowledge_vector;
CREATE TRIGGER trg_kv_tsv BEFORE INSERT OR UPDATE OF content ON t_knowledge_vector
  FOR EACH ROW EXECUTE FUNCTION kv_tsv_trigger();

-- 5. 回填已有数据（zhparser 分词）
UPDATE t_knowledge_vector SET tsv = to_tsvector('zhparser', COALESCE(content, ''));
