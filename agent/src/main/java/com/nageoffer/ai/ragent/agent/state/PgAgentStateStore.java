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
 */
@RequiredArgsConstructor
public class PgAgentStateStore implements AgentStateStore {

    private final AgentStateMapper agentStateMapper;

    @Override
    public void save(String userId, String sessionId, String key, State value) {
        agentStateMapper.upsert(userId, sessionId, key, JsonUtils.getJsonCodec().toJson(value));
    }

    @Override
    public void save(String userId, String sessionId, String key, List<? extends State> values) {
        agentStateMapper.upsert(userId, sessionId, key, JsonUtils.getJsonCodec().toJson(values));
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
        return agentStateMapper.exists(userId, sessionId);
    }

    @Override
    public void delete(String userId, String sessionId) {
        agentStateMapper.deleteBySession(userId, sessionId);
    }

    @Override
    public void delete(String userId, String sessionId, String key) {
        agentStateMapper.deleteByKey(userId, sessionId, key);
    }

    @Override
    public Set<String> listSessionIds(String userId) {
        return new LinkedHashSet<>(agentStateMapper.selectSessionIds(userId));
    }

    private String queryPayload(String userId, String sessionId, String key) {
        return agentStateMapper.selectPayload(userId, sessionId, key);
    }
}
