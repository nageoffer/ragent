-- Agent 可靠加购：在 ragent 执行。保留操作关联，不随会话级联删除。
CREATE TABLE IF NOT EXISTS t_agent_cart_operation (
    operation_id UUID PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL,
    conversation_id VARCHAR(64) NOT NULL,
    round_id VARCHAR(64) NOT NULL,
    sku_code VARCHAR(128) NOT NULL,
    quantity INTEGER NOT NULL CHECK (quantity BETWEEN 1 AND 99),
    authorization_ref TEXT NOT NULL,
    task_id VARCHAR(64) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'PROCESSING'
        CHECK (status IN ('PROCESSING','VERIFYING','PENDING','SUCCEEDED','REJECTED','CANCELLED')),
    stopped_at TIMESTAMPTZ,
    disconnected_at TIMESTAMPTZ,
    write_attempts INTEGER NOT NULL DEFAULT 0 CHECK (write_attempts BETWEEN 0 AND 3),
    lease_owner UUID,
    lease_until TIMESTAMPTZ,
    verify_round_id UUID,
    verify_count INTEGER NOT NULL DEFAULT 0 CHECK (verify_count BETWEEN 0 AND 6),
    verify_deadline TIMESTAMPTZ,
    last_verify_at TIMESTAMPTZ,
    result_message TEXT,
    quantity_after INTEGER,
    executed_at TIMESTAMPTZ,
    create_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    update_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, conversation_id, round_id, sku_code)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_cart_unresolved
    ON t_agent_cart_operation (user_id, sku_code)
    WHERE status IN ('PROCESSING','VERIFYING','PENDING');
CREATE INDEX IF NOT EXISTS idx_agent_cart_conversation ON t_agent_cart_operation (user_id, conversation_id);
CREATE TABLE IF NOT EXISTS t_agent_cart_attempt (
    id BIGSERIAL PRIMARY KEY,
    operation_id UUID NOT NULL REFERENCES t_agent_cart_operation(operation_id),
    attempt_no INTEGER NOT NULL CHECK (attempt_no BETWEEN 1 AND 3),
    status VARCHAR(24) NOT NULL DEFAULT 'RESERVED',
    start_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    end_time TIMESTAMPTZ,
    result_summary TEXT,
    UNIQUE (operation_id, attempt_no)
);
