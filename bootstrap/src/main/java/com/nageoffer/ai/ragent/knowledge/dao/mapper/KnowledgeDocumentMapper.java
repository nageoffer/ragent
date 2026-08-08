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
     * 仅当文档未删除且当前状态命中允许集合时，原子转换状态。
     */
    @Update("<script>"
            + "UPDATE t_knowledge_document "
            + "SET status = #{toStatus}, updated_by = #{updatedBy}, update_time = NOW() "
            + "WHERE id = #{docId} AND deleted = 0 AND status IN "
            + "<foreach collection='fromStatuses' item='source' open='(' close=')' separator=','>"
            + "#{source}"
            + "</foreach>"
            + "</script>")
    int casStatus(@Param("docId") String docId,
                  @Param("fromStatuses") List<String> fromStatuses,
                  @Param("toStatus") String toStatus,
                  @Param("updatedBy") String updatedBy);

    /**
     * 分块成功收尾：状态和块数在同一条 CAS SQL 中写回。
     */
    @Update("UPDATE t_knowledge_document "
            + "SET status = #{successStatus}, chunk_count = #{chunkCount}, "
            + "updated_by = #{updatedBy}, update_time = NOW() "
            + "WHERE id = #{docId} AND deleted = 0 AND status = #{runningStatus}")
    int markSuccessIfRunning(@Param("docId") String docId,
                             @Param("chunkCount") int chunkCount,
                             @Param("updatedBy") String updatedBy,
                             @Param("runningStatus") String runningStatus,
                             @Param("successStatus") String successStatus);

    /**
     * 写块前在当前事务内锁定文档行。
     */
    @Select("SELECT status FROM t_knowledge_document WHERE id = #{docId} FOR UPDATE")
    String selectStatusForUpdate(@Param("docId") String docId);
}
