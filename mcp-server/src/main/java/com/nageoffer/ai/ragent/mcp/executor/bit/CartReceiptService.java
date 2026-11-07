/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.ragent.mcp.executor.bit;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nageoffer.ai.ragent.mcp.dao.entity.ProductSkuDO;
import com.nageoffer.ai.ragent.mcp.dao.mapper.CartMapper;
import com.nageoffer.ai.ragent.mcp.dao.mapper.ProductSkuMapper;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** 回执与购物车变更共享业务库本地事务。 */
@Service
@RequiredArgsConstructor
public class CartReceiptService {
    public static final String META_OPERATION_ID = "com.nageoffer.ragent/cartOperationId";
    public static final String QUERY_TOOL = "query_cart_operation";
    private final JdbcTemplate jdbc;
    private final CartMapper cartMapper;
    private final ProductSkuMapper productSkuMapper;

    @Transactional(timeout = 10)
    public CallToolResult add(UUID operationId, String userId, String sku, int quantity) {
        // ON CONFLICT 等待并发事务结束；占位不能在加购前独立提交。
        jdbc.execute("SET LOCAL lock_timeout = '5s'");
        int inserted = jdbc.update("""
                INSERT INTO t_cart_operation_receipt
                    (operation_id,user_id,sku_code,action,quantity,result_status)
                VALUES (?,?,?,'add',?,'PROCESSING') ON CONFLICT (operation_id) DO NOTHING
                """, operationId, userId, sku, quantity);
        if (inserted == 0) {
            Map<String, Object> existing = receipt(operationId, userId);
            if (existing == null || !Objects.equals(sku, existing.get("skuCode"))
                    || !Objects.equals(quantity, existing.get("quantity"))) {
                return response(Map.of("status", "CONFLICT"), true, "操作身份或参数冲突，本次请求未执行");
            }
            return response(existing);
        }
        String status = "REJECTED";
        Integer total = null;
        String message;
        if (quantity < 1 || quantity > 99) {
            message = "增加量必须是 1 到 99 的整数，本次未加入";
        } else {
            ProductSkuDO product = productSkuMapper.selectOne(Wrappers.<ProductSkuDO>lambdaQuery()
                    .eq(ProductSkuDO::getSkuCode, sku));
            if (product == null || !"在售".equals(product.getStatus())) {
                message = "商品不存在或已下架，本次未加入";
            } else {
                total = cartMapper.addQuantity(userId, sku, quantity, product.getPrice(), 99);
                if (total == null) {
                    message = "加入后同款商品将超过 99 件，本次未加入";
                } else {
                    status = "SUCCEEDED";
                    message = String.format("已加入购物车：%s，本次新增 %d 件，该次执行后共 %d 件", sku, quantity, total);
                    if (product.getStock() < total) message += "；当前库存不足，下单时会重新检查";
                }
            }
        }
        jdbc.update("""
                UPDATE t_cart_operation_receipt SET result_status=?,quantity_after=?,result_message=?,executed_at=now()
                WHERE operation_id=?
                """, status, total, message, operationId);
        return response(receipt(operationId, userId));
    }

    public CallToolResult query(UUID id, String userId) {
        Map<String, Object> result = receipt(id, userId);
        // 不泄露其他用户的操作；NOT_FOUND 不是未执行证明。
        return result == null ? response(Map.of("operationId", id.toString(), "status", "NOT_FOUND"), false,
                "暂未查到本次回执，不能确定是否已加购") : response(result);
    }

    private Map<String, Object> receipt(UUID id, String userId) {
        return jdbc.query("SELECT * FROM t_cart_operation_receipt WHERE operation_id=? AND user_id=?",
                (rs, row) -> map(rs), id, userId).stream().findFirst().orElse(null);
    }

    private Map<String, Object> map(ResultSet rs) throws SQLException {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("operationId", rs.getString("operation_id"));
        value.put("userId", rs.getString("user_id"));
        value.put("skuCode", rs.getString("sku_code"));
        value.put("action", rs.getString("action"));
        value.put("quantity", rs.getInt("quantity"));
        value.put("status", rs.getString("result_status"));
        value.put("quantityAfter", rs.getObject("quantity_after"));
        value.put("message", rs.getString("result_message"));
        value.put("executedAt", rs.getTimestamp("executed_at").toInstant().toString());
        return value;
    }

    private CallToolResult response(Map<String, Object> result) {
        return response(result, "REJECTED".equals(result.get("status")), String.valueOf(result.get("message")));
    }

    public static CallToolResult response(Map<String, Object> result, boolean error, String message) {
        return CallToolResult.builder().structuredContent(result).addTextContent(message).isError(error).build();
    }
}
