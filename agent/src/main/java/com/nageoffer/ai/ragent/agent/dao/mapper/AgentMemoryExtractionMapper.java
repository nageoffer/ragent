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
import com.nageoffer.ai.ragent.agent.dao.entity.AgentMemoryExtractionDO;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 抽取台账 Mapper，水位是这张表上的聚合查询而不是独立游标行
 * 水位、在飞与重试都按用户不按会话：记忆只有一份，消费顺序必须是用户说话的先后
 */
@SuppressWarnings({"SqlDialectInspection", "SqlNoDataSourceInspection", "SqlResolve"})
public interface AgentMemoryExtractionMapper extends BaseMapper<AgentMemoryExtractionDO> {

    /**
     * 水位：已结束抽取的最大末条ID，IN 列表定义了哪几个状态推进水位
     */
    @Select("""
            SELECT max(to_message_id)
            FROM t_agent_memory_extraction
            WHERE user_id = #{userId}
              AND status IN ('WRITTEN', 'NOOP', 'DROPPED')
            """)
    String selectWatermark(@Param("userId") String userId);

    /**
     * 覆盖某条用户消息的那次已结束抽取的状态，没有返回 null
     * 批次在用户维度首尾相接，区间包含即批内成员；改版前按会话切的旧批次区间会互相交叠，但只罩得住改版前的消息
     */
    @Select("""
            SELECT status
            FROM t_agent_memory_extraction
            WHERE user_id = #{userId}
              AND status IN ('WRITTEN', 'NOOP', 'DROPPED')
              AND from_message_id <= #{messageId} AND to_message_id >= #{messageId}
            ORDER BY to_message_id DESC
            LIMIT 1
            """)
    String selectSettledStatusCovering(@Param("userId") String userId,
                                       @Param("messageId") String messageId);

    /**
     * 结算：只允许从 PROCESSING 出发，重复结算返回 0
     * attemptCount 一并回写，快照失配靠把它退回上一档来实现「不计入尝试次数」
     */
    @Update("""
            UPDATE t_agent_memory_extraction
            SET status = #{status}, decision_count = #{decisionCount},
                attempt_count = #{attemptCount}, settle_time = CURRENT_TIMESTAMP
            WHERE id = #{id} AND status = 'PROCESSING'
            """)
    int settle(@Param("id") String id,
               @Param("status") String status,
               @Param("decisionCount") int decisionCount,
               @Param("attemptCount") int attemptCount);

    /**
     * 僵尸回收：JVM 死在 Judge 中途会留下永不结算的在飞行，超时后按失配结掉腾出 claim
     * 这一路留着尝试次数不退档：能把进程带走的抽取不该无限重来
     */
    @Update("""
            UPDATE t_agent_memory_extraction
            SET status = 'CONFLICT', settle_time = CURRENT_TIMESTAMP
            WHERE user_id = #{userId}
              AND status = 'PROCESSING'
              AND create_time < CURRENT_TIMESTAMP - make_interval(mins => #{staleMinutes})
            """)
    int recycleStale(@Param("userId") String userId,
                     @Param("staleMinutes") int staleMinutes);

    /**
     * 同一区间已耗掉的尝试次数，行上的 attempt_count 已是结算后的有效值，不必再按状态过滤
     */
    @Select("""
            SELECT coalesce(max(attempt_count), 0)
            FROM t_agent_memory_extraction
            WHERE user_id = #{userId}
              AND to_message_id = #{toMessageId}
            """)
    int selectSpentAttempts(@Param("userId") String userId,
                            @Param("toMessageId") String toMessageId);
}
