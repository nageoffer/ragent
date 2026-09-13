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

package com.nageoffer.ai.ragent.agent.state;

import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.agent.dao.mapper.AgentStateMapper;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.State;
import io.agentscope.core.util.JsonUtils;
import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * AgentStateStore 的 PostgreSQL 实现
 * 官方 2.0.2 仅有 in-memory （内存）/ JSON 文件 / Redis / MySQL，本项目主存储为 PG，故自实现挂 t_agent_state
 * payload 是 AgentScope 自有编解码的不透明 JSON，不与业务表建立结构约定
 * 字段	说明
    user_id 用户ID，匿名会话为 __anon__/varchar(64)
    session_id 会话ID，即 AgentScope 的 sessionId/varchar(64)
    state_key 状态键，AgentScope 侧固定传 agent_state/varchar(64)
    payload 框架自有编码的状态 JSON，业务侧不解析/jsonb
    create_time 创建时间/timestamp without time zone
    update_time 更新时间/timestamp without time zone
 */
@RequiredArgsConstructor
public class PgAgentStateStore implements AgentStateStore {

    /**
     * 与官方 JsonFileAgentStateStore 对齐的匿名用户哨兵，PG 主键列不可为空
     */
    private static final String ANONYMOUS_USER = "__anon__";    //anonymous 的缩写，**匿名用户**

    private final AgentStateMapper agentStateMapper;

    @Override
    public void save(String userId, String sessionId, String key, State value) {
        agentStateMapper.upsert(safeUser(userId), sessionId, key, JsonUtils.getJsonCodec().toJson(value));
    }

    @Override
    public void save(String userId, String sessionId, String key, List<? extends State> values) {
        agentStateMapper.upsert(safeUser(userId), sessionId, key, JsonUtils.getJsonCodec().toJson(values));
    }

    @Override
    public <T extends State> Optional<T> get(String userId, String sessionId, String key, Class<T> type) {
        String payload = queryPayload(userId, sessionId, key);
        if (StrUtil.isBlank(payload)) {
            return Optional.empty();
        }
        return Optional.ofNullable(JsonUtils.getJsonCodec().fromJson(payload, type));
    }

    @Override
    public <T extends State> List<T> getList(String userId, String sessionId, String key, Class<T> itemType) {
        String payload = queryPayload(userId, sessionId, key);
        if (StrUtil.isBlank(payload)) {
            return List.of();
        }
        List<?> rawItems = JsonUtils.getJsonCodec().fromJson(payload, List.class);
        List<T> result = new ArrayList<>(rawItems.size());
        for (Object item : rawItems) {
            result.add(JsonUtils.getJsonCodec().convertValue(item, itemType));
        }
        return result;
    }

    @Override
    public boolean exists(String userId, String sessionId) {
        return agentStateMapper.exists(safeUser(userId), sessionId);
    }

    @Override
    public void delete(String userId, String sessionId) {
        agentStateMapper.deleteBySession(safeUser(userId), sessionId);
    }

    @Override
    public void delete(String userId, String sessionId, String key) {
        agentStateMapper.deleteByKey(safeUser(userId), sessionId, key);
    }

    @Override
    public Set<String> listSessionIds(String userId) {
        return new LinkedHashSet<>(agentStateMapper.selectSessionIds(safeUser(userId)));
    }

    private String queryPayload(String userId, String sessionId, String key) {
        return agentStateMapper.selectPayload(safeUser(userId), sessionId, key);
    }

    private String safeUser(String userId) {
        return StrUtil.isBlank(userId) ? ANONYMOUS_USER : userId;
    }
}
