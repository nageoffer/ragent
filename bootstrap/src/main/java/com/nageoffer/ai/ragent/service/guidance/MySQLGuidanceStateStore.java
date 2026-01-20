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

package com.nageoffer.ai.ragent.service.guidance;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.google.gson.Gson;
import com.nageoffer.ai.ragent.dao.entity.GuidanceStateDO;
import com.nageoffer.ai.ragent.dao.mapper.GuidanceStateMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 基于 MySQL 的引导式问答状态存储实现类
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MySQLGuidanceStateStore implements GuidanceStateStore {

    private static final Gson GSON = new Gson();

    private final GuidanceStateMapper guidanceStateMapper;

    @Override
    public GuidanceState load(String conversationId, String userId) {
        if (StrUtil.isBlank(conversationId) || StrUtil.isBlank(userId)) {
            return null;
        }
        GuidanceStateDO record = guidanceStateMapper.selectOne(
                Wrappers.lambdaQuery(GuidanceStateDO.class)
                        .eq(GuidanceStateDO::getConversationId, conversationId)
                        .eq(GuidanceStateDO::getUserId, userId)
                        .eq(GuidanceStateDO::getDeleted, 0)
                        .orderByDesc(GuidanceStateDO::getUpdateTime)
                        .last("limit 1")
        );
        if (record == null || StrUtil.isBlank(record.getStateJson())) {
            return null;
        }
        try {
            return GSON.fromJson(record.getStateJson(), GuidanceState.class);
        } catch (Exception ex) {
            log.warn("解析引导式问答状态失败, conversationId: {}, userId: {}", conversationId, userId, ex);
            return null;
        }
    }

    @Override
    public void save(String conversationId, String userId, GuidanceState state) {
        if (StrUtil.isBlank(conversationId) || StrUtil.isBlank(userId) || state == null) {
            return;
        }
        String payload = GSON.toJson(state);

        GuidanceStateDO update = GuidanceStateDO.builder()
                .stateJson(payload)
                .build();
        int updated = guidanceStateMapper.update(
                update,
                Wrappers.lambdaUpdate(GuidanceStateDO.class)
                        .eq(GuidanceStateDO::getConversationId, conversationId)
                        .eq(GuidanceStateDO::getUserId, userId)
                        .eq(GuidanceStateDO::getDeleted, 0)
        );
        if (updated > 0) {
            return;
        }

        GuidanceStateDO record = GuidanceStateDO.builder()
                .conversationId(conversationId)
                .userId(userId)
                .stateJson(payload)
                .build();
        guidanceStateMapper.insert(record);
    }

    @Override
    public void clear(String conversationId, String userId) {
        if (StrUtil.isBlank(conversationId) || StrUtil.isBlank(userId)) {
            return;
        }
        guidanceStateMapper.delete(
                Wrappers.lambdaQuery(GuidanceStateDO.class)
                        .eq(GuidanceStateDO::getConversationId, conversationId)
                        .eq(GuidanceStateDO::getUserId, userId)
        );
    }

}
