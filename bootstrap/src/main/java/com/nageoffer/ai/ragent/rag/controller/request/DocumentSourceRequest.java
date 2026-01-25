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

package com.nageoffer.ai.ragent.rag.controller.request;

import com.nageoffer.ai.ragent.ingestion.domain.enums.SourceType;
import lombok.Data;

import java.util.Map;

/**
 * 文档源请求对象
 * 用于接收创建摄取任务时的文档来源信息，包括来源类型、位置、文件名及访问凭证
 */
@Data
public class DocumentSourceRequest {

    /**
     * 文档源类型
     * 如 file、url、feishu、s3 等
     */
    private SourceType type;

    /**
     * 文档的访问位置
     * 可以是文件路径、URL地址或第三方平台的资源标识
     */
    private String location;

    /**
     * 文档的文件名
     */
    private String fileName;

    /**
     * 访问文档所需的凭证信息
     * 如 API Token、用户名密码等键值对
     */
    private Map<String, String> credentials;
}
