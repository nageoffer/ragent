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

package com.nageoffer.ai.ragent.audit.dao.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.nageoffer.ai.ragent.framework.database.JsonbTypeHandler;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName(value = "t_biz_change_log", autoResultMap = true)
public class BizChangeLogDO {

    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    private String bizType;

    private String bizId;

    private String operationType;

    private String actionDesc;

    @TableField(typeHandler = JsonbTypeHandler.class)
    private String beforeSnapshot;

    @TableField(typeHandler = JsonbTypeHandler.class)
    private String afterSnapshot;

    @TableField(typeHandler = JsonbTypeHandler.class)
    private String changeDiff;

    private String operatorId;

    private String operatorName;

    private String operatorRole;

    private Boolean success;

    private String errorMessage;

    private String className;

    private String methodName;

    private String ip;

    private String userAgent;

    @TableField(fill = FieldFill.INSERT)
    private Date createTime;
}
