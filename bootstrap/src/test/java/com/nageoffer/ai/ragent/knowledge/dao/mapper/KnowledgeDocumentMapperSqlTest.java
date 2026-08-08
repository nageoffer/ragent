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
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/** 检查 Mapper 注解 SQL 是否包含逻辑删除、来源状态和写入前行锁等关键约束。 */
class KnowledgeDocumentMapperSqlTest {

    @Test
    void casStatusChecksSourceStatusAndLogicalDeletion() throws NoSuchMethodException {
        Method method = KnowledgeDocumentMapper.class.getMethod(
                "casStatus", String.class, List.class, String.class, String.class);
        String sql = normalized(method.getAnnotation(Update.class).value());

        assertThat(sql).contains("deleted = 0");
        assertThat(sql).contains("status in");
        assertThat(sql).contains("update_time = now()");
    }

    @Test
    void writeGuardQueryLocksTheDocumentWithoutLogicalDeleteFilter() throws NoSuchMethodException {
        Method method = KnowledgeDocumentMapper.class.getMethod("selectStatusForUpdate", String.class);
        String sql = normalized(method.getAnnotation(Select.class).value());

        assertThat(sql).contains("where id = #{docid}");
        assertThat(sql).endsWith("for update");
        assertThat(sql).doesNotContain("deleted = 0");
    }

    private String normalized(String[] fragments) {
        return String.join(" ", fragments).toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }
}
