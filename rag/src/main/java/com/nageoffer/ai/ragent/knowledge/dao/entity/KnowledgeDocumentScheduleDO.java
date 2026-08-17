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
 * 知识库文档定时刷新任务实体
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("t_knowledge_document_schedule")
public class KnowledgeDocumentScheduleDO {

    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    /**
     * 文档 ID
     */
    private String docId;

    /**
     * 知识库 ID
     */
    private String kbId;

    /**
     * 定时表达式
     */
    private String cronExpr;

    /**
     * 是否启用定时
     */
    private Integer enabled;

    /**
     * 下次执行时间
     */
    private Date nextRunTime;

    /**
     * 上次执行时间
     */
    private Date lastRunTime;

    /**
     * 上次成功时间
     */
    private Date lastSuccessTime;

    /**
     * 上次执行状态
     */
    private String lastStatus;

    /**
     * 上次错误信息
     */
    private String lastError;

    /**
     * 上次 ETag
     */
    private String lastEtag;

    /**
     * 上次 Last-Modified
     */
    private String lastModified;

    /**
     * 上次内容哈希
     */
    private String lastContentHash;

    /**
     * 锁持有者
     */
    private String lockOwner;

    /**
     * 锁到期时间
     */
    private Date lockUntil;

    @TableField(fill = FieldFill.INSERT)
    private Date createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Date updateTime;
}
