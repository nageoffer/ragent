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

import com.nageoffer.ai.ragent.agent.dao.entity.AgentMemoryCursorDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 长期记忆后台游标 Mapper：条件推进只校验后台消费进度，不校验记忆内容
 */
@SuppressWarnings({"SqlDialectInspection", "SqlNoDataSourceInspection", "SqlResolve"})
public interface AgentMemoryCursorMapper {

    @Select("""
            SELECT user_id, last_message_id, create_time, update_time
            FROM t_agent_memory_cursor
            WHERE user_id = #{userId}
            """)
    AgentMemoryCursorDO selectByUserId(@Param("userId") String userId);

    /**
     * 首次纳入长期记忆时建行，必须在本轮用户发言入库之前调用：游标停在此刻已有的最后一条用户发言上，
     * 接入前的历史不倒灌，本轮及之后入库的发言 id 都比它大，照常待整理；没有任何发言时为 NULL
     * 已有行不动，开关怎么切都不会重置进度；并发建行由主键冲突收敛
     */
    @Insert("""
            INSERT INTO t_agent_memory_cursor (user_id, last_message_id, create_time, update_time)
            SELECT #{userId}, max(id), CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
            FROM t_agent_message
            WHERE user_id = #{userId} AND role = 'user'
            ON CONFLICT (user_id) DO NOTHING
            """)
    void insertIfAbsent(@Param("userId") String userId);

    /**
     * 只有游标还停在读取时的位置才推进，返回 0 说明期间已被别的处理推过，这次结果不能落库
     */
    @Update("""
            UPDATE t_agent_memory_cursor
            SET last_message_id = #{nextMessageId}, update_time = CURRENT_TIMESTAMP
            WHERE user_id = #{userId}
              AND last_message_id IS NOT DISTINCT FROM #{expectedMessageId,jdbcType=VARCHAR}
            """)
    int advance(@Param("userId") String userId,
                @Param("expectedMessageId") String expectedMessageId,
                @Param("nextMessageId") String nextMessageId);
}
