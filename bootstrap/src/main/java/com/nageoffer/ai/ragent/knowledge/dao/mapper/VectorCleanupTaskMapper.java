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

package com.nageoffer.ai.ragent.knowledge.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.nageoffer.ai.ragent.knowledge.dao.entity.VectorCleanupTaskDO;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.util.Date;

public interface VectorCleanupTaskMapper extends BaseMapper<VectorCleanupTaskDO> {

    @Update("UPDATE t_vector_cleanup_task "
            + "SET status = #{running}, lock_owner = #{lockOwner}, lock_until = #{lockUntil}, update_time = NOW() "
            + "WHERE id = #{id} AND status = #{pending} "
            + "AND (next_retry_time IS NULL OR next_retry_time <= #{now})")
    int claimProcessing(@Param("id") String id, @Param("lockOwner") String lockOwner,
                        @Param("lockUntil") Date lockUntil, @Param("now") Date now,
                        @Param("running") String runningCode, @Param("pending") String pendingCode);

    @Update("UPDATE t_vector_cleanup_task "
            + "SET status = #{success}, lock_owner = NULL, lock_until = NULL, error_message = NULL, update_time = NOW() "
            + "WHERE id = #{id} AND status = #{running} AND lock_owner = #{lockOwner}")
    int markSuccessIfOwned(@Param("id") String id, @Param("lockOwner") String lockOwner,
                           @Param("success") String successCode, @Param("running") String runningCode);

    @Update("UPDATE t_vector_cleanup_task "
            + "SET status = #{pending}, retry_count = #{retryCount}, next_retry_time = #{nextRetryTime}, "
            + "error_message = #{errorMessage}, lock_owner = NULL, lock_until = NULL, update_time = NOW() "
            + "WHERE id = #{id} AND status = #{running} AND lock_owner = #{lockOwner}")
    int markRetryIfOwned(@Param("id") String id, @Param("lockOwner") String lockOwner,
                         @Param("pending") String pendingCode, @Param("running") String runningCode,
                         @Param("retryCount") int retryCount, @Param("nextRetryTime") Date nextRetryTime,
                         @Param("errorMessage") String errorMessage);

    @Update("UPDATE t_vector_cleanup_task "
            + "SET status = #{failed}, retry_count = #{retryCount}, error_message = #{errorMessage}, "
            + "lock_owner = NULL, lock_until = NULL, update_time = NOW() "
            + "WHERE id = #{id} AND status = #{running} AND lock_owner = #{lockOwner}")
    int markFailedIfOwned(@Param("id") String id, @Param("lockOwner") String lockOwner,
                          @Param("failed") String failedCode, @Param("running") String runningCode,
                          @Param("retryCount") int retryCount, @Param("errorMessage") String errorMessage);

    @Update("UPDATE t_vector_cleanup_task "
            + "SET status = #{pending}, lock_owner = NULL, lock_until = NULL, update_time = NOW() "
            + "WHERE status = #{running} AND lock_until <= #{now}")
    int recoverExpiredProcessing(@Param("now") Date now,
                                 @Param("running") String runningCode, @Param("pending") String pendingCode);
}
