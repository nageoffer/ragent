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
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.nageoffer.ai.ragent.framework.database.JsonbTypeHandler;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 知识库文档实体
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("t_knowledge_document")
public class KnowledgeDocumentDO {

    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    /**
     * 所属知识库 ID
     */
    private String kbId;

    /**
     * 文档名称
     */
    private String docName;

    /**
     * 来源类型：file / url
     */
    private String sourceType;

    /**
     * 来源位置（URL）
     */
    private String sourceLocation;

    /**
     * 是否开启定时拉取：1-启用，0-禁用
     */
    private Integer scheduleEnabled;

    /**
     * 定时表达式（cron）
     */
    private String scheduleCron;

    /**
     * 是否启用：1-启用，0-禁用
     */
    private Integer enabled;

    /**
     * 分块数（chunk 数量）
     */
    private Integer chunkCount;

    /**
     * 文件地址（存 OSS / NFS 等路径）
     */
    private String fileUrl;

    /**
     * 文件类型：pdf / markdown / docx 等
     */
    private String fileType;

    /**
     * 真实 MIME 类型
     * <p>
     * 与展示用的 {@link #fileType} 是两个不同的事实：这个由字节探测得出、只服务解析路由；
     * 那个由扩展名得出、只服务图标与预览。把展示短标签当 MIME 用过，那是一整类串线缺陷的根源
     */
    private String mimeType;

    /**
     * 文件大小（单位字节）
     */
    private Long fileSize;

    /**
     * 处理模式：chunk / pipeline
     * - chunk: 使用分块策略直接分块
     * - pipeline: 使用数据通道进行清洗处理
     */
    private String processMode;

    /**
     * 文档级摄取配置（JSON）：解析档位 + 分块预算
     * 仅在 processMode=chunk 时有效
     * <p>
     * 取代原先的"分块策略列 + 自由 JSON 配置列"：策略枚举在真实链路上不产生任何差异，
     * 而自由 JSON 的键由前端手工拼、后端分三处用硬编码字符串探测
     */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String ingestionSpec;

    /**
     * 数据通道（Pipeline）ID
     * 仅在 processMode=pipeline 时有效
     */
    private String pipelineId;

    /**
     * 状态：
     * - pending：待向量化
     * - running：向量化中
     * - failed：向量化失败
     * - success：向量化完成
     */
    private String status;

    /**
     * 创建人
     */
    private String createdBy;

    /**
     * 修改人
     */
    private String updatedBy;

    @TableField(fill = FieldFill.INSERT)
    private Date createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Date updateTime;

    /**
     * 是否删除：0-正常，1-删除
     */
    @TableLogic
    private Integer deleted;
}
