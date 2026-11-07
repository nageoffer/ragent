-- 可靠加购回执：必须与 t_cart 位于同一 PostgreSQL 数据库。
CREATE TABLE IF NOT EXISTS t_cart_operation_receipt (
    operation_id UUID PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL,
    sku_code VARCHAR(128) NOT NULL,
    action VARCHAR(16) NOT NULL CHECK (action = 'add'),
    quantity INTEGER NOT NULL,
    result_status VARCHAR(24) NOT NULL CHECK (result_status IN ('PROCESSING','SUCCEEDED','REJECTED')),
    quantity_after INTEGER,
    result_message TEXT,
    executed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- 首期不清理回执；后续清理详情必须保留操作 ID、参数绑定和去重结论。
