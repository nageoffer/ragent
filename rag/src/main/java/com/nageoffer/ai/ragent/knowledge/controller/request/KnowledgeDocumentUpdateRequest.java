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

package com.nageoffer.ai.ragent.knowledge.controller.request;

import lombok.Data;

@Data
public class KnowledgeDocumentUpdateRequest {

    /**
     * 文档名称
     */
    private String docName;

    /**
     * 处理模式：chunk / pipeline
     */
    private String processMode;


    /**
     * 摄取配置 JSON（CHUNK 模式），如 {"parseProfile":"fast","maxChars":1024,"overlapChars":128}，
     * 字段可缺省，落库前由 IngestionSpecCodec 校验并归一化
     */
    private String ingestionSpec;

    /**
     * Pipeline ID（PIPELINE 模式）
     */
    private String pipelineId;

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
}
