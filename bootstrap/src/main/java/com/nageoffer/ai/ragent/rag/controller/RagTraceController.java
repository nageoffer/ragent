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
import com.nageoffer.ai.ragent.rag.controller.request.RagTraceRunPageRequest;
import com.nageoffer.ai.ragent.rag.controller.vo.RagTraceDetailVO;
import com.nageoffer.ai.ragent.rag.controller.vo.RagTraceNodeVO;
import com.nageoffer.ai.ragent.rag.controller.vo.RagTraceRunVO;
import com.nageoffer.ai.ragent.rag.service.RagTraceQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * RAG Trace 查询接口
 */
@RestController
@RequiredArgsConstructor
public class RagTraceController {

    private final RagTraceQueryService ragTraceQueryService;

    /**
     * 分页查询链路运行记录
     */
    @GetMapping("/rag/traces/runs")
    public Result<IPage<RagTraceRunVO>> pageRuns(RagTraceRunPageRequest request) {
        return Results.success(ragTraceQueryService.pageRuns(request));
    }

    /**
     * 查询链路详情（包含节点）
     */
    @GetMapping("/rag/traces/runs/{runId}")
    public Result<RagTraceDetailVO> detail(@PathVariable String runId) {
        return Results.success(ragTraceQueryService.detail(runId));
    }

    /**
     * 仅查询链路节点
     */
    @GetMapping("/rag/traces/runs/{runId}/nodes")
    public Result<List<RagTraceNodeVO>> nodes(@PathVariable String runId) {
        return Results.success(ragTraceQueryService.listNodes(runId));
    }
}
