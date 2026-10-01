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

package com.nageoffer.ai.ragent.agent.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.nageoffer.ai.ragent.agent.dao.entity.AgentMemoryDO;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 长期记忆条目 Mapper，失效两条走条件 UPDATE 换取影响行数
 */
@SuppressWarnings({"SqlDialectInspection", "SqlNoDataSourceInspection", "SqlResolve"})
public interface AgentMemoryMapper extends BaseMapper<AgentMemoryDO> {

    /**
     * 取代旧条目：一道闸同时挡幻觉ID、已失效ID、他人ID，调用方只认返回 1
     */
    @Update("""
            UPDATE t_agent_memory
            SET invalid_at = CURRENT_TIMESTAMP, superseded_by = #{newId}
            WHERE id = #{oldId} AND user_id = #{userId} AND invalid_at IS NULL
            """)
    int supersede(@Param("userId") String userId,
                  @Param("oldId") String oldId,
                  @Param("newId") String newId);

    /**
     * 置 invalid_at 不留后继；用户撤回与容量淘汰共用这一条，库上不区分，谁干的只看日志
     */
    @Update("""
            UPDATE t_agent_memory
            SET invalid_at = CURRENT_TIMESTAMP
            WHERE id = #{oldId} AND user_id = #{userId} AND invalid_at IS NULL
            """)
    int retract(@Param("userId") String userId, @Param("oldId") String oldId);

    /**
     * 清空：该用户全部生效条目一次失效，不留后继；同事务内 CURRENT_TIMESTAMP 相同，失效时刻即这批台账的结算时刻
     */
    @Update("""
            UPDATE t_agent_memory
            SET invalid_at = CURRENT_TIMESTAMP
            WHERE user_id = #{userId} AND invalid_at IS NULL
            """)
    int retractAll(@Param("userId") String userId);
}
