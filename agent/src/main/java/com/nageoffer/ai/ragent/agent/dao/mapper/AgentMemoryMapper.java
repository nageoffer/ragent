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

import java.util.Date;

/**
 * 长期记忆条目 Mapper，修改全走条件 UPDATE 换取影响行数：目标不存在、已失效或不属于该用户都返回 0
 */
@SuppressWarnings({"SqlDialectInspection", "SqlNoDataSourceInspection", "SqlResolve"})
public interface AgentMemoryMapper extends BaseMapper<AgentMemoryDO> {

    /**
     * 置 invalid_at；用户删除与容量淘汰共用这一条，区别只在反馈清单里
     */
    @Update("""
            UPDATE t_agent_memory
            SET invalid_at = CURRENT_TIMESTAMP, update_time = CURRENT_TIMESTAMP
            WHERE id = #{id} AND user_id = #{userId} AND invalid_at IS NULL
            """)
    int invalidate(@Param("userId") String userId, @Param("id") String id);

    /**
     * 原行改写：正文、写入入口与依据换成这次表达，id 与 create_time 不变；正文相同即只换依据
     */
    @Update("""
            UPDATE t_agent_memory
            SET content = #{content}, source_type = #{sourceType}, source_message_id = #{sourceMessageId},
                expressed_at = #{expressedAt}, update_time = CURRENT_TIMESTAMP
            WHERE id = #{id} AND user_id = #{userId} AND invalid_at IS NULL
            """)
    int updateContent(@Param("userId") String userId,
                      @Param("id") String id,
                      @Param("content") String content,
                      @Param("sourceType") String sourceType,
                      @Param("sourceMessageId") String sourceMessageId,
                      @Param("expressedAt") Date expressedAt);
}
