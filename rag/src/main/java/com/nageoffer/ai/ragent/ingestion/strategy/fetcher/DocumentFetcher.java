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

package com.nageoffer.ai.ragent.ingestion.strategy.fetcher;

import com.nageoffer.ai.ragent.ingestion.domain.context.DocumentSource;
import com.nageoffer.ai.ragent.ingestion.domain.enums.SourceType;

/**
 * 文档提取接口，用于从不同源获取文档数据
 */
public interface DocumentFetcher {

    /**
     * 获取支持的源类型
     *
     * @return 对应的源类型
     */
    SourceType supportedType();

    /**
     * 从给定的源中抓取文档
     *
     * @param source 文档数据源
     * @return 抓取结果，包含文档内容及其元数据
     */
    FetchResult fetch(DocumentSource source);
}
