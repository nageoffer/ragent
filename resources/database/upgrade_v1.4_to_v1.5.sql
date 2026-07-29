-- ragent v1.4 -> v1.5 升级脚本
-- issue #42：文档删除与分块并发竞态修复
-- 1) t_knowledge_document.status 新增 deleting 状态（无需 DDL，VARCHAR(16) 兼容，仅说明）
-- 2) 新增两张 outbox 清理任务表，用于 PgVector / 文件存储删除的最终一致兜底
--    status：pending/running/success/failed

CREATE TABLE IF NOT EXISTS t_vector_cleanup_task (
    id              VARCHAR(20)  NOT NULL PRIMARY KEY,
    doc_id          VARCHAR(20)  NOT NULL,
    collection_name VARCHAR(64)  NOT NULL,
    status          VARCHAR(16)  NOT NULL DEFAULT 'pending',
    retry_count     INTEGER      NOT NULL DEFAULT 0,
    next_retry_time TIMESTAMP,
    lock_owner      VARCHAR(128),
    lock_until      TIMESTAMP,
    error_message   TEXT,
    create_time     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_vct_status_next ON t_vector_cleanup_task (status, next_retry_time);
CREATE INDEX IF NOT EXISTS idx_vct_status_lock ON t_vector_cleanup_task (status, lock_until);
CREATE INDEX IF NOT EXISTS idx_vct_doc_id ON t_vector_cleanup_task (doc_id);

COMMENT ON TABLE t_vector_cleanup_task IS '向量清理任务表（outbox）';
COMMENT ON COLUMN t_vector_cleanup_task.doc_id IS '文档ID';
COMMENT ON COLUMN t_vector_cleanup_task.collection_name IS '向量集合名';
COMMENT ON COLUMN t_vector_cleanup_task.status IS '状态：pending/running/success/failed';
COMMENT ON COLUMN t_vector_cleanup_task.retry_count IS '已重试次数';
COMMENT ON COLUMN t_vector_cleanup_task.next_retry_time IS '下次执行时间';
COMMENT ON COLUMN t_vector_cleanup_task.lock_owner IS '当前领取者';
COMMENT ON COLUMN t_vector_cleanup_task.lock_until IS '领取租约到期时间';
COMMENT ON COLUMN t_vector_cleanup_task.error_message IS '错误信息';

CREATE TABLE IF NOT EXISTS t_file_cleanup_task (
    id              VARCHAR(20)   NOT NULL PRIMARY KEY,
    file_url        VARCHAR(1024) NOT NULL,
    status          VARCHAR(16)   NOT NULL DEFAULT 'pending',
    retry_count     INTEGER       NOT NULL DEFAULT 0,
    next_retry_time TIMESTAMP,
    lock_owner      VARCHAR(128),
    lock_until      TIMESTAMP,
    error_message   TEXT,
    create_time     TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_fct_status_next ON t_file_cleanup_task (status, next_retry_time);
CREATE INDEX IF NOT EXISTS idx_fct_status_lock ON t_file_cleanup_task (status, lock_until);

COMMENT ON TABLE t_file_cleanup_task IS '文件清理任务表（outbox）';
COMMENT ON COLUMN t_file_cleanup_task.file_url IS '文件存储地址';
COMMENT ON COLUMN t_file_cleanup_task.status IS '状态：pending/running/success/failed';
COMMENT ON COLUMN t_file_cleanup_task.retry_count IS '已重试次数';
COMMENT ON COLUMN t_file_cleanup_task.next_retry_time IS '下次执行时间';
COMMENT ON COLUMN t_file_cleanup_task.lock_owner IS '当前领取者';
COMMENT ON COLUMN t_file_cleanup_task.lock_until IS '领取租约到期时间';
COMMENT ON COLUMN t_file_cleanup_task.error_message IS '错误信息';
