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

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeDocumentDO;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface KnowledgeDocumentMapper extends BaseMapper<KnowledgeDocumentDO> {

    /**
     * CAS 抢占文档状态：仅当当前状态命中 fromStatuses 且未删除时，原子置为 toStatus。
     *
     * @return 受影响行数；0 表示并发冲突（状态已被其它流程改写）
     */
    @Update("<script>"
            + "UPDATE t_knowledge_document "
            + "SET status = #{toStatus}, update_time = NOW() "
            + "WHERE id = #{docId} AND deleted = 0 "
            + "AND status IN "
            + "<foreach collection='fromStatuses' item='s' open='(' close=')' separator=','>#{s}</foreach>"
            + "</script>")
    int casStatus(@Param("docId") String docId,
                  @Param("fromStatuses") List<String> fromStatuses,
                  @Param("toStatus") String toStatus);

    /**
     * 仅查询文档当前状态（不走逻辑删除过滤，供分块中段二次校验使用）。
     *
     * @return status 字符串；文档不存在时返回 null
     */
    @Select("SELECT status FROM t_knowledge_document WHERE id = #{docId}")
    String selectStatusById(@Param("docId") String docId);
}
