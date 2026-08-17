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

package com.nageoffer.ai.ragent.rag.controller.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 知识图谱可视化视图
 * <p>
 * 规整为前端直接可用的 {nodes, edges} 结构，与底层图存储（Neo4j 等）解耦：
 * 后端只认 LightRAG 归一化后的图谱语义，换存储不影响前端
 */
@Data
@Builder
public class GraphViewVO {

    /**
     * 节点集
     */
    private List<Node> nodes;

    /**
     * 边集
     */
    private List<Edge> edges;

    /**
     * 是否因节点数上限被截断（供前端提示「图谱过大，仅展示部分」）
     */
    private boolean truncated;

    /**
     * 图谱节点
     */
    @Data
    @Builder
    public static class Node {

        /**
         * 节点唯一 id，边按此关联
         */
        private String id;

        /**
         * 展示名（实体名）
         */
        private String name;

        /**
         * 实体类型，供前端按类型着色
         */
        private String type;

        /**
         * 实体描述，供悬浮详情
         */
        private String description;
    }

    /**
     * 图谱边
     */
    @Data
    @Builder
    public static class Edge {

        /**
         * 边唯一 id
         */
        private String id;

        /**
         * 源节点 id
         */
        private String source;

        /**
         * 目标节点 id
         */
        private String target;

        /**
         * 关系标签（关键词 / 类型）
         */
        private String label;

        /**
         * 关系描述，供前端悬浮展示可读的关系说明
         */
        private String description;
    }
}
