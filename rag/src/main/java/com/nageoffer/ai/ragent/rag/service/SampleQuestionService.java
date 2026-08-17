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

package com.nageoffer.ai.ragent.rag.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.nageoffer.ai.ragent.rag.controller.request.SampleQuestionCreateRequest;
import com.nageoffer.ai.ragent.rag.controller.request.SampleQuestionPageRequest;
import com.nageoffer.ai.ragent.rag.controller.request.SampleQuestionUpdateRequest;
import com.nageoffer.ai.ragent.rag.controller.vo.SampleQuestionVO;

import java.util.List;

public interface SampleQuestionService {

    /**
     * 创建示例问题
     */
    String create(SampleQuestionCreateRequest requestParam);

    /**
     * 更新示例问题
     */
    void update(String id, SampleQuestionUpdateRequest requestParam);

    /**
     * 删除示例问题
     */
    void delete(String id);

    /**
     * 查询示例问题详情
     */
    SampleQuestionVO queryById(String id);

    /**
     * 分页查询示例问题列表
     */
    IPage<SampleQuestionVO> pageQuery(SampleQuestionPageRequest requestParam);

    /**
     * 随机获取示例问题列表
     */
    List<SampleQuestionVO> listRandomQuestions();
}
