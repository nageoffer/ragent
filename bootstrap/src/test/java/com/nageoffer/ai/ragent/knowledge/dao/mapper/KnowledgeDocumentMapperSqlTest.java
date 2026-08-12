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

package com.nageoffer.ai.ragent.knowledge.dao.mapper;

import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeDocumentMapperSqlTest {

    // 验证写入锁 SQL 同时检查 deleted、status、document_version 并使用 FOR UPDATE
    @Test
    void writeFenceSqlContainsAllOwnershipPredicates() throws Exception {
        Method method = KnowledgeDocumentMapper.class.getMethod(
                "selectRunningForUpdate", String.class, String.class);
        String sql = String.join(" ", method.getAnnotation(Select.class).value()).toLowerCase(Locale.ROOT);

        assertThat(sql).contains("deleted = 0", "status = 'running'", "document_version", "for update");
    }
}
