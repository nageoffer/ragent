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

package com.nageoffer.ai.ragent.ingestion.domain.context;

import com.nageoffer.ai.ragent.ingestion.domain.enums.SourceType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 文档源实体类
 * 描述文档的来源信息，包括源类型、访问位置、文件名称以及访问凭证等
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DocumentSource {

    /**
     * 文档源类型
     * 如 File、Url、Feishu、S3 等
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
