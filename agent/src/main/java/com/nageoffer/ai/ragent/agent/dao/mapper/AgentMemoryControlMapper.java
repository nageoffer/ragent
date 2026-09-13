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

import com.nageoffer.ai.ragent.agent.dao.entity.AgentMemoryControlDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Date;

/**
 * 长期记忆控制面 Mapper，提交期的串行点就在这张表的行锁上
 * 
 * [开启数据库事务 @Transactional]
       │
       ├──► 1. mapper.ensureExists(userId, now);   // 确保行记录必定存在
       │
       ├──► 2. mapper.selectForUpdate(userId);     // 关键！锁定该行，其他并发事务在此排队
       │
       ├──► 3. 执行记忆写入操作 (如插入 t_agent_memory)
       │
       ├──► 4. mapper.bumpRevision(userId);        // 记忆有更新，版本号 +1
       │
[提交数据库事务 COMMIT]                            // 释放行锁，允许下一个排队事务进入
 */
@SuppressWarnings({"SqlDialectInspection", "SqlNoDataSourceInspection", "SqlResolve"})
public interface AgentMemoryControlMapper {

    /**
     * 懒创建，并发下靠 ON CONFLICT 收敛；now 必须来自应用时钟，与消息 create_time 同源避免钟差
     */
    @Insert("""
            INSERT INTO t_agent_memory_control (user_id, revision, create_time, update_time)
            VALUES (#{userId}, 0, #{now}, #{now})
            ON CONFLICT (user_id) DO NOTHING
            """)
    void ensureExists(@Param("userId") String userId, @Param("now") Date now);

    @Select("""
            SELECT user_id, revision, create_time, update_time
            FROM t_agent_memory_control
            WHERE user_id = #{userId}
            """)
    AgentMemoryControlDO selectByUserId(@Param("userId") String userId);

    /**
     * 提交事务的第一句：拿到行锁，同用户的提交从这里开始排队（悲观锁）
     */
    @Select("""
            SELECT user_id, revision, create_time, update_time
            FROM t_agent_memory_control
            WHERE user_id = #{userId}
            FOR UPDATE
            """)
    AgentMemoryControlDO selectForUpdate(@Param("userId") String userId);

    /**
     * 记忆集变更后推版本号；NOOP (空操作)不涨版本号，所以单靠版本号挡不住重复写入
     */
    @Update("""
            UPDATE t_agent_memory_control
            SET revision = revision + 1, update_time = CURRENT_TIMESTAMP
            WHERE user_id = #{userId}
            """)
    void bumpRevision(@Param("userId") String userId);
}
