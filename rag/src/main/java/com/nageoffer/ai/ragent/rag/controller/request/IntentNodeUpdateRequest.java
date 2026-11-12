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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSetter;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
// HTTP 反序列化通过 setter 记录字段是否出现，不能走 Lombok 全参构造器。
@NoArgsConstructor(onConstructor_ = @JsonCreator)
@AllArgsConstructor
@Builder
public class IntentNodeUpdateRequest {

    private String name;
    private Integer level;
    private String parentCode;
    @JsonIgnore
    private boolean parentCodeProvided;
    private String description;
    private List<String> examples;
    private String mcpToolId;
    private Integer requireConfirm;
    private String collectionName;
    private List<String> collectionNames;
    private Integer topK;
    private Integer kind;
    private Integer sortOrder;
    private Integer enabled;
    private String promptSnippet;
    private String promptTemplate;

    @JsonSetter("parentCode")
    public void setParentCode(String parentCode) {
        this.parentCode = parentCode;
        this.parentCodeProvided = true;
    }

    /**
     * 显式 null 表示移至根节点；未提供字段时保留原父节点。
     */
    @JsonIgnore
    public boolean hasParentCode() {
        return parentCodeProvided || parentCode != null;
    }
}
