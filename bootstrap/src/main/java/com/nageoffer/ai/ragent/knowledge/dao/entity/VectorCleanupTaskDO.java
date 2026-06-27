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

package com.nageoffer.ai.ragent.knowledge.dao.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 向量清理任务（outbox）。
 * 删除主事务内写入，由 CleanupTaskWorker 异步执行 PgVector 删除并保证最终一致。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("t_vector_cleanup_task")
public class VectorCleanupTaskDO {

    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    /** 文档 ID */
    private String docId;

    /** 向量集合名（collection_name） */
    private String collectionName;

    /** 任务状态：pending/success/failed，见 CleanupTaskStatus */
    private String status;

    /** 已重试次数 */
    private Integer retryCount;

    /** 下次可执行时间（退避重试） */
    private Date nextRetryTime;

    /** 当前领取者 */
    private String lockOwner;

    /** 领取租约到期时间 */
    private Date lockUntil;

    /** 最近一次错误信息 */
    private String errorMessage;

    @TableField(fill = FieldFill.INSERT)
    private Date createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Date updateTime;
}
