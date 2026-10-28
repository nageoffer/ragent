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

package com.nageoffer.ai.ragent.agent.cart;

import com.nageoffer.ai.ragent.agent.config.ConditionalOnAgentEngine;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Repository
@ConditionalOnAgentEngine
public class CartOperationStore {
    private static final String UNRESOLVED = "status IN ('PROCESSING','VERIFYING','PENDING')";
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public CartOperationStore(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(manager);
    }

    public CartOperation accept(String user, String conversation, String round, String sku,
                                int quantity, String authorization, String task) {
        return tx.execute(s -> {
            // 与删除共用会话行锁；删除先提交时，不允许在已删除会话创建操作。
            var conversations = jdbc.queryForList("SELECT conversation_id FROM t_agent_conversation "
                    + "WHERE user_id=? AND conversation_id=? AND deleted=0 FOR UPDATE", user, conversation);
            if (conversations.isEmpty()) throw new ClientException("会话不存在，未开始加购");
            jdbc.update("""
                    INSERT INTO t_agent_cart_operation
                    (operation_id,user_id,conversation_id,round_id,sku_code,quantity,authorization_ref,task_id)
                    VALUES (?,?,?,?,?,?,?,?) ON CONFLICT DO NOTHING
                    """, UUID.randomUUID(), user, conversation, round, sku, quantity, authorization, task);
            List<CartOperation> same = jdbc.query("""
                    SELECT * FROM t_agent_cart_operation WHERE user_id=? AND conversation_id=? AND round_id=? AND sku_code=?
                    """, this::row, user, conversation, round, sku);
            if (!same.isEmpty()) {
                CartOperation op = same.get(0);
                if (op.quantity() != quantity) throw new ClientException("同轮同款增加量冲突，请澄清；没有创建新的加购");
                return op;
            }
            throw new ClientException("该商品还有一次结果未确定的加购，请先核实原操作，再确认是否额外加购");
        });
    }

    public CartOperation get(UUID id, String user) {
        return jdbc.query("SELECT * FROM t_agent_cart_operation WHERE operation_id=? AND user_id=?",
                this::row, id, user).stream().findFirst().orElseThrow(() -> new ClientException("操作不存在或无权访问"));
    }

    public CartOperation unresolved(String user, String sku) {
        return jdbc.query("SELECT * FROM t_agent_cart_operation WHERE user_id=? AND sku_code=? AND " + UNRESOLVED,
                this::row, user, sku).stream().findFirst().orElse(null);
    }

    public List<CartOperation> list(String user, String conversation) {
        return jdbc.query("SELECT * FROM t_agent_cart_operation WHERE user_id=? AND conversation_id=? ORDER BY create_time",
                this::row, user, conversation);
    }

    public boolean authorizationValid(CartOperation op) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM t_agent_message WHERE id=? AND user_id=? AND conversation_id=?
                AND deleted=0 AND blocks @> '[{"kind":"confirm","status":"approved"}]'::jsonb
                """, Integer.class, op.authorizationRef(), op.userId(), op.conversationId());
        return count != null && count == 1;
    }

    public void guardDeletion(String user, String conversation) {
        jdbc.queryForList("SELECT conversation_id FROM t_agent_conversation WHERE user_id=? AND conversation_id=? AND deleted=0 FOR UPDATE",
                user, conversation);
        Integer count = jdbc.queryForObject("SELECT count(*) FROM t_agent_cart_operation WHERE user_id=? AND conversation_id=? AND "
                + UNRESOLVED, Integer.class, user, conversation);
        if (count != null && count > 0) throw new ClientException("会话还有待核实的加购，请先核实后再删除");
    }

    /** 租约超过一次有界执行周期，进程退出后允许用户接手核实。 */
    public boolean acquire(UUID id, String user, UUID owner) {
        return jdbc.update("""
                UPDATE t_agent_cart_operation SET lease_owner=?,lease_until=now()+interval '45 seconds',update_time=now()
                WHERE operation_id=? AND user_id=? AND status IN ('PROCESSING','VERIFYING','PENDING')
                AND (lease_until IS NULL OR lease_until<now())
                """, owner, id, user) == 1;
    }

    public void release(UUID id, UUID owner) {
        jdbc.update("""
                UPDATE t_agent_cart_operation SET lease_owner=NULL,lease_until=NULL,
                status=CASE WHEN status IN ('PROCESSING','VERIFYING') THEN 'PENDING' ELSE status END,update_time=now()
                WHERE operation_id=? AND lease_owner=?
                """, id, owner);
    }

    public int reserveWrite(UUID id, UUID owner, boolean resumed, String task) {
        return Objects.requireNonNull(tx.execute(s -> {
            List<Integer> attempts = jdbc.query("""
                    UPDATE t_agent_cart_operation SET write_attempts=write_attempts+1,status='PROCESSING',
                        task_id=?,update_time=now()
                    WHERE operation_id=? AND lease_owner=? AND lease_until>now()
                        AND status IN ('PROCESSING','VERIFYING','PENDING')
                        AND stopped_at IS NULL AND write_attempts<3
                        AND (disconnected_at IS NULL OR ?)
                    RETURNING write_attempts
                    """, (rs, n) -> rs.getInt(1), task, id, owner, resumed);
            if (attempts.isEmpty()) return 0;
            int attempt = attempts.get(0);
            jdbc.update("INSERT INTO t_agent_cart_attempt(operation_id,attempt_no) VALUES (?,?)", id, attempt);
            return attempt;
        }));
    }

    /** 与停止更新按同一行串行：这一步成功后，该尝试视为已经开始。 */
    public boolean maySend(UUID id, UUID owner) {
        return jdbc.update("""
                UPDATE t_agent_cart_operation SET update_time=now() WHERE operation_id=? AND lease_owner=?
                AND lease_until>now() AND stopped_at IS NULL AND status='PROCESSING'
                """, id, owner) == 1;
    }

    public void finishAttempt(UUID id, int attempt, boolean response) {
        jdbc.update("""
                UPDATE t_agent_cart_attempt SET status=?,end_time=now() WHERE operation_id=? AND attempt_no=?
                """, response ? "RESPONDED" : "UNKNOWN", id, attempt);
    }

    /** 已存在的核实周期不因刷新重置，下一周期至少等到前一截止时间之后。 */
    public boolean beginVerification(UUID id, UUID owner) {
        jdbc.update("""
                UPDATE t_agent_cart_operation SET verify_round_id=?,verify_count=0,
                    verify_deadline=now()+interval '30 seconds',last_verify_at=NULL,update_time=now()
                WHERE operation_id=? AND lease_owner=? AND lease_until>now()
                    AND (verify_deadline IS NULL OR verify_deadline<=now())
                """, UUID.randomUUID(), id, owner);
        return jdbc.update("""
                UPDATE t_agent_cart_operation SET status='VERIFYING',update_time=now()
                WHERE operation_id=? AND lease_owner=? AND lease_until>now()
                AND status IN ('PROCESSING','VERIFYING','PENDING') AND verify_deadline>now() AND verify_count<6
                """, id, owner) == 1;
    }

    /** 用数据库时间计算下一次可核实时刻，不修改已有轮次及预算。 */
    public Instant nextVerificationAt(UUID id, String user) {
        List<Instant> times = jdbc.query("""
                SELECT CASE WHEN available_at>now() THEN available_at END FROM (
                    SELECT GREATEST(lease_until,
                        CASE WHEN verify_count>=6 THEN verify_deadline END,
                        last_verify_at+interval '1 second') AS available_at
                    FROM t_agent_cart_operation WHERE operation_id=? AND user_id=?
                    AND status IN ('PROCESSING','VERIFYING','PENDING')
                ) availability
                """, (rs, n) -> instant(rs, 1), id, user);
        return times.isEmpty() ? null : times.get(0);
    }

    private Instant instant(ResultSet rs, int column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    public long remainingMillis(UUID id, UUID owner) {
        return jdbc.query("""
                SELECT GREATEST(0,extract(epoch FROM (verify_deadline-now()))*1000)::bigint
                FROM t_agent_cart_operation WHERE operation_id=? AND lease_owner=? AND lease_until>now()
                """, (rs,n) -> rs.getLong(1), id, owner).stream().findFirst().orElse(0L);
    }

    public boolean reserveQuery(UUID id, UUID owner) {
        return jdbc.update("""
                UPDATE t_agent_cart_operation SET verify_count=verify_count+1,last_verify_at=now(),update_time=now()
                WHERE operation_id=? AND lease_owner=? AND lease_until>now()
                AND status IN ('PROCESSING','VERIFYING','PENDING') AND verify_deadline>now() AND verify_count<6
                AND (last_verify_at IS NULL OR last_verify_at<=now()-interval '1 second')
                """, id, owner) == 1;
    }

    public void applyReceipt(CartOperation op, Map<?, ?> receipt) {
        // 外部文本或普通工具错误不构成业务终态，必须完整匹配回执身份与参数。
        if (!op.operationId().toString().equals(receipt.get("operationId"))
                || !op.userId().equals(receipt.get("userId")) || !op.skuCode().equals(receipt.get("skuCode"))
                || !"add".equals(receipt.get("action")) || !(receipt.get("quantity") instanceof Number q)
                || q.intValue() != op.quantity()) return;
        String status = String.valueOf(receipt.get("status"));
        if (!List.of("SUCCEEDED", "REJECTED").contains(status)) return;
        Instant executed;
        try { executed = Instant.parse(String.valueOf(receipt.get("executedAt"))); }
        catch (RuntimeException e) { return; }
        Object after = receipt.get("quantityAfter");
        if ("SUCCEEDED".equals(status) && !(after instanceof Number)) return;
        jdbc.update("""
                UPDATE t_agent_cart_operation SET status=?,result_message=?,quantity_after=?,executed_at=?,update_time=now()
                WHERE operation_id=? AND status IN ('PROCESSING','VERIFYING','PENDING')
                """, status, String.valueOf(receipt.get("message")), after, Timestamp.from(executed), op.operationId());
    }

    public CartOperation stopOperation(UUID id, String user) {
        get(id, user);
        jdbc.update("""
                UPDATE t_agent_cart_operation SET stopped_at=COALESCE(stopped_at,now()),
                    status=CASE WHEN write_attempts=0 THEN 'CANCELLED' ELSE 'PENDING' END,update_time=now()
                WHERE operation_id=? AND user_id=? AND status IN ('PROCESSING','VERIFYING','PENDING')
                """, id, user);
        return get(id, user);
    }

    public void stopTask(String task, boolean disconnected) {
        String field = disconnected ? "disconnected_at" : "stopped_at";
        jdbc.update("UPDATE t_agent_cart_operation SET " + field + "=COALESCE(" + field + ",now()),"
                + "status=CASE WHEN write_attempts=0 THEN 'CANCELLED' ELSE 'PENDING' END,update_time=now() "
                + "WHERE task_id=? AND " + UNRESOLVED, task);
    }

    private CartOperation row(ResultSet rs, int n) throws SQLException {
        return new CartOperation(rs.getObject("operation_id", UUID.class), rs.getString("user_id"),
                rs.getString("conversation_id"), rs.getString("round_id"), rs.getString("sku_code"),
                rs.getInt("quantity"), rs.getString("authorization_ref"), rs.getString("task_id"),
                rs.getString("status"), rs.getInt("write_attempts"), instant(rs,"stopped_at"), instant(rs,"disconnected_at"),
                rs.getString("result_message"), (Integer) rs.getObject("quantity_after"), instant(rs,"executed_at"), null);
    }
    private Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
