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

package com.nageoffer.ai.ragent.audit.constant;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class BizChangeBizType {

    public static final String KNOWLEDGE_BASE = "KNOWLEDGE_BASE";
    public static final String KNOWLEDGE_DOCUMENT = "KNOWLEDGE_DOCUMENT";
    public static final String KNOWLEDGE_CHUNK = "KNOWLEDGE_CHUNK";
    public static final String INGESTION_PIPELINE = "INGESTION_PIPELINE";
    public static final String INGESTION_TASK = "INGESTION_TASK";
    public static final String INTENT_TREE = "INTENT_TREE";
    public static final String QUERY_TERM_MAPPING = "QUERY_TERM_MAPPING";
    public static final String SAMPLE_QUESTION = "SAMPLE_QUESTION";
    public static final String USER = "USER";
    public static final String AGENT_PROFILE = "AGENT_PROFILE";
}
