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

package com.nageoffer.ai.ragent.rag.service.impl;

import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.nageoffer.ai.ragent.audit.support.BizChangeLogContext;
import com.nageoffer.ai.ragent.rag.config.OrchestrationProperties;
import com.nageoffer.ai.ragent.rag.core.prompt.AgentPromptCacheManager;
import com.nageoffer.ai.ragent.rag.core.prompt.AgentPromptResolver;
import com.nageoffer.ai.ragent.rag.dao.entity.AgentProfileDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.AgentProfileMapper;
import com.nageoffer.ai.ragent.rag.dao.mapper.AgentPromptMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentProfileAdminServiceImplTest {

    private final AgentProfileMapper profileMapper = mock(AgentProfileMapper.class);
    private final AgentPromptCacheManager cacheManager = mock(AgentPromptCacheManager.class);
    private final AgentPromptResolver promptResolver = mock(AgentPromptResolver.class);
    private final OrchestrationProperties orchestration = new OrchestrationProperties();
    private final AgentProfileAdminServiceImpl service = new AgentProfileAdminServiceImpl(profileMapper,
            mock(AgentPromptMapper.class), promptResolver, cacheManager,
            orchestration, mock(BizChangeLogContext.class));

    @BeforeAll
    static void initLambdaCache() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), ""), AgentProfileDO.class);
    }

    /**
     * 激活只写 active 一列：读出的整行若原样写回，会把并发刚保存的名称、描述改回旧值
     */
    @Test
    void activateWritesOnlyActiveFlag() {
        when(profileMapper.selectById("agent-b")).thenReturn(AgentProfileDO.builder()
                .id("agent-b").name("读出时的旧名称").description("读出时的旧描述").builtin(0).active(0).build());

        service.activate("agent-b");

        ArgumentCaptor<AgentProfileDO> written = ArgumentCaptor.forClass(AgentProfileDO.class);
        verify(profileMapper).updateById(written.capture());
        assertThat(written.getValue()).usingRecursiveComparison()
                .isEqualTo(AgentProfileDO.builder().id("agent-b").active(1).build());
        verify(cacheManager).clearCache();
    }
}
