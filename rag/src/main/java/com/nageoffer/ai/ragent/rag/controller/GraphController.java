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

import com.nageoffer.ai.ragent.framework.convention.Result;
import com.nageoffer.ai.ragent.framework.web.Results;
import com.nageoffer.ai.ragent.rag.controller.vo.GraphViewVO;
import com.nageoffer.ai.ragent.rag.core.graph.GraphQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 知识图谱可视化控制器
 * 提供后台图谱浏览：拉取子图、检索实体标签
 * <p>
 * 图谱通道未启用时，服务层以业务异常提示，前端据此给出「未启用」引导，路由本身始终存在
 */
@RestController
@RequestMapping("/admin/kg")
@RequiredArgsConstructor
public class GraphController {

    private final GraphQueryService graphQueryService;

    /**
     * 拉取图谱子图
     *
     * @param entity     起点实体名，空则取全图
     * @param collection 知识库 collectionName，限定只看该库子图，空则不限
     * @param doc        文档 id，限定只看该文档子图，优先级高于 collection，空则不限
     * @param depth      子图深度，默认 2
     * @param limit      节点上限，默认 200
     */
    @GetMapping("/graph")
    public Result<GraphViewVO> graph(@RequestParam(required = false) String entity,
                                     @RequestParam(required = false) String collection,
                                     @RequestParam(required = false) String doc,
                                     @RequestParam(defaultValue = "2") int depth,
                                     @RequestParam(defaultValue = "200") int limit) {
        return Results.success(graphQueryService.getGraph(entity, collection, doc, depth, limit));
    }

    /**
     * 检索实体标签，供可视化搜索框；keyword 为空取热门标签
     */
    @GetMapping("/labels")
    public Result<List<String>> labels(@RequestParam(required = false) String keyword,
                                       @RequestParam(defaultValue = "50") int limit) {
        return Results.success(graphQueryService.searchEntities(keyword, limit));
    }
}
