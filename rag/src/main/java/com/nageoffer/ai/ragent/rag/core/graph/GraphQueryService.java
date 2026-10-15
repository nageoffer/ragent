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

package com.nageoffer.ai.ragent.rag.core.graph;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.nageoffer.ai.ragent.framework.exception.ServiceException;
import com.nageoffer.ai.ragent.rag.controller.vo.GraphViewVO;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 知识图谱可视化查询服务
 * <p>
 * 后台可视化的查询入口：经 {@link LightRagClient} 取图，映射为前端视图 {@link GraphViewVO}
 * 图谱通道未启用（rag.graph.type=none）时无 LightRagClient bean，直接以业务异常提示，路由本身始终存在
 * <p>
 * 只依赖 LightRAG 归一化后的图谱语义，不直连 Neo4j：无需在后端引入图数据库驱动与连接配置，换存储亦不受影响
 */
@Service
@RequiredArgsConstructor
public class GraphQueryService {

    private final ObjectProvider<LightRagClient> lightRagClientProvider;

    /**
     * 查询图谱子图
     *
     * @param entity     起点实体名，空则取全图
     * @param collection 知识库 collectionName，限定只看该库子图，空则不限
     * @param doc        文档 id，限定只看该文档子图，优先级高于 collection，空则不限
     * @param depth      子图深度，非正取默认 2
     * @param limit      节点上限，非正取默认 200，上限 1000
     */
    public GraphViewVO getGraph(String entity, String collection, String doc, int depth, int limit) {
        LightRagClient client = requireClient();
        int maxDepth = depth > 0 ? depth : 2;
        int maxNodes = limit > 0 ? Math.min(limit, 1000) : 200;
        String label = StrUtil.isNotBlank(entity) ? entity : "*";
        // 文档范围优先于知识库范围；归属判定在 mapGraph 中统一按 GraphFileSource 解析后的分量等值匹配
        String scopedDoc = StrUtil.isNotBlank(doc) ? doc : null;
        String scopedCollection = scopedDoc == null && StrUtil.isNotBlank(collection) ? collection : null;
        // 有范围过滤时向 LightRAG 拉宽到服务端上限，保证按 file_path 过滤后仍有足量节点
        int fetchNodes = scopedDoc != null || scopedCollection != null ? 1000 : maxNodes;
        return mapGraph(client.fetchGraph(label, maxDepth, fetchNodes), scopedCollection, scopedDoc, maxNodes);
    }

    /**
     * 检索实体标签，供可视化搜索框；keyword 为空取热门标签
     */
    public List<String> searchEntities(String keyword, int limit) {
        return requireClient().fetchLabels(keyword, limit);
    }

    private LightRagClient requireClient() {
        LightRagClient client = lightRagClientProvider.getIfAvailable();
        if (client == null) {
            throw new ServiceException("知识图谱通道未启用（rag.graph.type=none）");
        }
        return client;
    }

    /**
     * 将 LightRAG 原始 {nodes,edges,is_truncated} 映射为前端视图
     * <p>
     * 节点展示名取 properties.entity_id、回退 labels[0]、再回退内部 id；类型 / 描述取 properties 对应字段
     * 边标签取 properties.keywords、回退 type，关系描述取 properties.description；缺失 id 的边用 source-target 兜底，防御式读取
     * <p>
     * collection 或 doc 非空时按节点 properties.file_path 的来源分量精确过滤，并丢弃两端不全保留的悬空边；
     * 过滤后仍超 limit 则截断到 limit 并置 truncated，file_path 仅用于内部过滤、不进 VO
     *
     * @param collection 来源知识库过滤值，null 表示不限知识库
     * @param doc        来源文档过滤值，null 表示不限文档；非空时优先于 collection
     * @param limit      展示节点上限
     */
    private GraphViewVO mapGraph(JsonNode root, String collection, String doc, int limit) {
        List<GraphViewVO.Node> nodes = new ArrayList<>();
        List<GraphViewVO.Edge> edges = new ArrayList<>();
        boolean truncated = false;
        if (root != null) {
            truncated = root.path("is_truncated").asBoolean(false);
            Set<String> keptIds = new HashSet<>();
            JsonNode nodeArray = root.path("nodes");
            if (nodeArray.isArray()) {
                for (JsonNode node : nodeArray) {
                    String id = node.path("id").asText("");
                    if (StrUtil.isBlank(id)) {
                        continue;
                    }
                    JsonNode props = node.path("properties");
                    // LightRAG 会用 <SEP> 合并同一节点的多个来源；逐段解析后等值匹配，避免 kb 误命中 kb_hr
                    if (!matchesSource(props.path("file_path").asText(""), collection, doc)) {
                        continue;
                    }
                    // 过滤后按展示上限截断：达上限即标记截断、停止收节点（LightRAG 已按跳数+度数排序，取前 limit 最相关）
                    if (nodes.size() >= limit) {
                        truncated = true;
                        break;
                    }
                    String name = props.path("entity_id").asText("");
                    if (StrUtil.isBlank(name)) {
                        JsonNode labels = node.path("labels");
                        if (labels.isArray() && !labels.isEmpty()) {
                            name = labels.get(0).asText("");
                        }
                    }
                    keptIds.add(id);
                    nodes.add(GraphViewVO.Node.builder()
                            .id(id)
                            .name(StrUtil.isNotBlank(name) ? name : id)
                            .type(props.path("entity_type").asText(""))
                            .description(cleanMerged(props.path("description").asText(""), "\n"))
                            .build());
                }
            }
            JsonNode edgeArray = root.path("edges");
            if (edgeArray.isArray()) {
                for (JsonNode edge : edgeArray) {
                    String source = edge.path("source").asText("");
                    String target = edge.path("target").asText("");
                    if (StrUtil.isBlank(source) || StrUtil.isBlank(target)) {
                        continue;
                    }
                    // 两端都在保留集才保留该边，剔除因过滤 / 截断产生的悬空边
                    if (!keptIds.contains(source) || !keptIds.contains(target)) {
                        continue;
                    }
                    JsonNode props = edge.path("properties");
                    // 关键词同为多来源合并，按 <SEP> 切开去重后用斜杠内联展示
                    String label = cleanMerged(props.path("keywords").asText(""), " / ");
                    if (StrUtil.isBlank(label)) {
                        label = edge.path("type").asText("");
                    }
                    edges.add(GraphViewVO.Edge.builder()
                            .id(edge.path("id").asText(source + "-" + target))
                            .source(source)
                            .target(target)
                            .label(label)
                            .description(cleanMerged(props.path("description").asText(""), "\n"))
                            .build());
                }
            }
        }
        return GraphViewVO.builder().nodes(nodes).edges(edges).truncated(truncated).build();
    }

    /**
     * 判断 LightRAG 的一个或多个合并来源是否命中查询范围
     * <p>
     * 启用范围过滤后，无法解析的来源按不命中处理，避免未知来源绕过知识库 / 文档隔离
     */
    private static boolean matchesSource(String filePath, String collection, String doc) {
        if (collection == null && doc == null) {
            return true;
        }
        if (StrUtil.isBlank(filePath)) {
            return false;
        }
        for (String part : filePath.split("<SEP>")) {
            GraphFileSource source = GraphFileSource.parse(part.trim());
            if (source == null) {
                continue;
            }
            if (doc != null ? doc.equals(source.docId()) : collection.equals(source.collectionName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 归一 LightRAG 的多来源合并串
     * <p>
     * {@code <SEP>} 是 LightRAG 合并同一实体 / 关系跨来源描述、关键词时的内部分隔符，直接透出到前端不友好
     * 这里按其切开、逐段去空白与去重后用 joiner 重组：描述用换行让每条来源独立成行，关键词用斜杠内联
     *
     * @param raw    LightRAG 原始串，可能含 0 至多个 {@code <SEP>}
     * @param joiner 去重后各段的重组连接符
     */
    private static String cleanMerged(String raw, String joiner) {
        if (StrUtil.isBlank(raw)) {
            return "";
        }
        List<String> seen = new ArrayList<>();
        for (String part : raw.split("<SEP>")) {
            String piece = part.trim();
            if (StrUtil.isNotBlank(piece) && !seen.contains(piece)) {
                seen.add(piece);
            }
        }
        return String.join(joiner, seen);
    }
}
