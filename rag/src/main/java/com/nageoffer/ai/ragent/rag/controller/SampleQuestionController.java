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

package com.nageoffer.ai.ragent.rag.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.nageoffer.ai.ragent.framework.convention.Result;
import com.nageoffer.ai.ragent.framework.web.Results;
import com.nageoffer.ai.ragent.rag.controller.request.SampleQuestionCreateRequest;
import com.nageoffer.ai.ragent.rag.controller.request.SampleQuestionPageRequest;
import com.nageoffer.ai.ragent.rag.controller.request.SampleQuestionUpdateRequest;
import com.nageoffer.ai.ragent.rag.controller.vo.SampleQuestionVO;
import com.nageoffer.ai.ragent.rag.service.SampleQuestionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 示例问题控制器（欢迎页展示）
 */
@RestController
@RequiredArgsConstructor
public class SampleQuestionController {

    private final SampleQuestionService sampleQuestionService;

    /**
     * 随机获取示例问题列表
     */
    @GetMapping("/rag/sample-questions")
    public Result<List<SampleQuestionVO>> listSampleQuestions() {
        return Results.success(sampleQuestionService.listRandomQuestions());
    }

    /**
     * 分页查询示例问题列表
     */
    @GetMapping("/sample-questions")
    public Result<IPage<SampleQuestionVO>> pageQuery(SampleQuestionPageRequest requestParam) {
        return Results.success(sampleQuestionService.pageQuery(requestParam));
    }

    /**
     * 查询示例问题详情
     */
    @GetMapping("/sample-questions/{id}")
    public Result<SampleQuestionVO> queryById(@PathVariable String id) {
        return Results.success(sampleQuestionService.queryById(id));
    }

    /**
     * 创建示例问题
     */
    @PostMapping("/sample-questions")
    public Result<String> create(@RequestBody SampleQuestionCreateRequest requestParam) {
        return Results.success(sampleQuestionService.create(requestParam));
    }

    /**
     * 更新示例问题
     */
    @PutMapping("/sample-questions/{id}")
    public Result<Void> update(@PathVariable String id, @RequestBody SampleQuestionUpdateRequest requestParam) {
        sampleQuestionService.update(id, requestParam);
        return Results.success();
    }

    /**
     * 删除示例问题
     */
    @DeleteMapping("/sample-questions/{id}")
    public Result<Void> delete(@PathVariable String id) {
        sampleQuestionService.delete(id);
        return Results.success();
    }
}
