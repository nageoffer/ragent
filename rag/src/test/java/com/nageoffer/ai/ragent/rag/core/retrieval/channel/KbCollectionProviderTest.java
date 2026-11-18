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

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeBaseDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KbCollectionProviderTest {

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void readsModelBindingWithCollectionAndKeepsMissingBindingVisible() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), KnowledgeBaseDO.class);
        KnowledgeBaseMapper mapper = mock(KnowledgeBaseMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of(
                kb("kb-a", "model-a"), kb("kb-b", null), kb(" ", "model-c")));

        Map<String, String> models = new KbCollectionProvider(mapper).listActiveCollectionModels();

        assertThat(models).containsOnlyKeys("kb-a", "kb-b").containsEntry("kb-a", "model-a").containsEntry("kb-b", null);
        ArgumentCaptor<LambdaQueryWrapper<KnowledgeBaseDO>> query = ArgumentCaptor.forClass((Class) LambdaQueryWrapper.class);
        verify(mapper).selectList(query.capture());
        assertThat(query.getValue().getSqlSelect()).contains("collection_name", "embedding_model");
        assertThat(query.getValue().getSqlSegment()).contains("deleted =");
        assertThat(query.getValue().getParamNameValuePairs()).containsValue(0);
    }

    @Test
    void existingCollectionListStillFiltersEmptyNamesAndDuplicates() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), KnowledgeBaseDO.class);
        KnowledgeBaseMapper mapper = mock(KnowledgeBaseMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of(
                kb("kb-a", "model-a"), kb("kb-a", "model-a"), kb(null, null), kb(" ", null), kb("kb-b", null)));

        assertThat(new KbCollectionProvider(mapper).listActiveCollections()).containsExactly("kb-a", "kb-b");
    }

    private KnowledgeBaseDO kb(String collection, String model) {
        return KnowledgeBaseDO.builder().collectionName(collection).embeddingModel(model).build();
    }
}
