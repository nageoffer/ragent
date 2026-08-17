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

package com.nageoffer.ai.ragent.rag.core.retrieval.channel;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeBaseDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 有效知识库 collection 提供者
 * <p>
 * 全局检索（向量 / 关键词）的唯一「全库范围」来源：只返回未删除（deleted=0）知识库的 collection
 * 两路全局检索共用此处，保证「全局」语义一致——都以知识库表为准，
 * 而非各自用通配（如 ES 的 kb_*），后者会命中已删除库残留、测试库、旧 schema 等无效索引
 */
@Component
@RequiredArgsConstructor
public class KbCollectionProvider {

    private final KnowledgeBaseMapper knowledgeBaseMapper;

    /**
     * 返回所有有效知识库的 collection 名称（去重、去空）
     */
    public List<String> listActiveCollections() {
        List<KnowledgeBaseDO> kbList = knowledgeBaseMapper.selectList(
                Wrappers.lambdaQuery(KnowledgeBaseDO.class)
                        .select(KnowledgeBaseDO::getCollectionName)
                        .eq(KnowledgeBaseDO::getDeleted, 0)
        );
        return kbList.stream()
                .map(KnowledgeBaseDO::getCollectionName)
                .filter(StrUtil::isNotBlank)
                .distinct()
                .toList();
    }
}
