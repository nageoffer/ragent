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

package com.nageoffer.ai.ragent.ingestion.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.google.gson.Gson;
import com.nageoffer.ai.ragent.controller.request.IntentNodeCreateRequest;
import com.nageoffer.ai.ragent.controller.request.IntentNodeUpdateRequest;
import com.nageoffer.ai.ragent.controller.vo.IntentNodeTreeVO;
import com.nageoffer.ai.ragent.dao.entity.IntentNodeDO;
import com.nageoffer.ai.ragent.dao.mapper.IntentNodeMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.nageoffer.ai.ragent.enums.IntentKind;
import com.nageoffer.ai.ragent.enums.IntentLevel;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.framework.exception.ServiceException;
import com.nageoffer.ai.ragent.rag.intent.IntentNode;
import com.nageoffer.ai.ragent.rag.intent.IntentTreeFactory;
import com.nageoffer.ai.ragent.ingestion.service.IntentTreeService;
import lombok.RequiredArgsConstructor;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import static com.nageoffer.ai.ragent.enums.IntentLevel.DOMAIN;

@Service
@RequiredArgsConstructor
public class IntentTreeServiceImpl extends ServiceImpl<IntentNodeMapper, IntentNodeDO> implements IntentTreeService {

    private final KnowledgeBaseMapper knowledgeBaseMapper;

    private static final Gson GSON = new Gson();

    @Override
    public List<IntentNodeTreeVO> getFullTree() {
        List<IntentNodeDO> list = this.list(new LambdaQueryWrapper<IntentNodeDO>()
                .eq(IntentNodeDO::getDeleted, 0)
                .orderByAsc(IntentNodeDO::getSortOrder, IntentNodeDO::getId));

        // 先按 parentCode 分组
        Map<String, List<IntentNodeDO>> parentMap = list.stream()
                .collect(Collectors.groupingBy(node -> {
                    String parent = node.getParentCode();
                    return parent == null ? "ROOT" : parent;
                }));

        // 根节点：parentCode 为空
        List<IntentNodeDO> roots = parentMap.getOrDefault("ROOT", Collections.emptyList());

        // 递归构建树
        List<IntentNodeTreeVO> tree = new ArrayList<>();
        for (IntentNodeDO root : roots) {
            tree.add(buildTree(root, parentMap));
        }
        return tree;
    }

    private IntentNodeTreeVO buildTree(IntentNodeDO current,
                                       Map<String, List<IntentNodeDO>> parentMap) {
        IntentNodeTreeVO result = BeanUtil.toBean(current, IntentNodeTreeVO.class);
        List<IntentNodeDO> children = parentMap.getOrDefault(current.getIntentCode(), Collections.emptyList());

        if (!CollectionUtils.isEmpty(children)) {
            List<IntentNodeTreeVO> childVOs = children.stream()
                    .map(child -> buildTree(child, parentMap))
                    .collect(Collectors.toList());

            result.setChildren(childVOs);
        }

        return result;
    }

    @Override
    public String createNode(IntentNodeCreateRequest requestParam) {
        // 简单重复校验：intentCode 不允许重复
        long count = this.count(new LambdaQueryWrapper<IntentNodeDO>()
                .eq(IntentNodeDO::getIntentCode, requestParam.getIntentCode())
                .eq(IntentNodeDO::getDeleted, 0));
        if (count > 0) {
            throw new ClientException("意图标识已存在: " + requestParam.getIntentCode());
        }

        if (Objects.equals(requestParam.getLevel(), DOMAIN.getCode())
                && Objects.equals(requestParam.getKind(), IntentKind.KB.getCode())
                && StrUtil.isBlank(requestParam.getKbId())) {
            throw new ClientException("Domain类型的RAG检索意图识别时，必须指定目标知识库");
        }

        IntentNodeDO node = IntentNodeDO.builder()
                .intentCode(requestParam.getIntentCode())
                .kbId(
                        StrUtil.isNotBlank(requestParam.getKbId()) ? Long.parseLong(requestParam.getKbId()) : null
                )
                .collectionName(
                        StrUtil.isNotBlank(requestParam.getKbId()) ? knowledgeBaseMapper.selectById(requestParam.getKbId()).getCollectionName() : null
                )
                .name(requestParam.getName())
                .level(requestParam.getLevel())
                .parentCode(requestParam.getParentCode())
                .description(requestParam.getDescription())
                .mcpToolId(requestParam.getMcpToolId())
                .examples(
                        requestParam.getExamples() == null ? null : GSON.toJson(requestParam.getExamples())
                )
                .kind(
                        requestParam.getKind() == null ? 0 : requestParam.getKind()
                )
                .sortOrder(
                        requestParam.getSortOrder() == null ? 0 : requestParam.getSortOrder()
                )
                .enabled(
                        requestParam.getEnabled() == null ? 1 : requestParam.getEnabled()
                )
                .createBy(UserContext.getUsername())
                .updateBy(UserContext.getUsername())
                .paramPromptTemplate(requestParam.getParamPromptTemplate())
                .promptSnippet(requestParam.getPromptSnippet())
                .promptTemplate(requestParam.getPromptTemplate())
                .deleted(0)
                .build();

        this.save(node);
        return String.valueOf(node.getId());
    }

    @Override
    public void updateNode(Long id, IntentNodeUpdateRequest req) {
        IntentNodeDO node = this.getById(id);
        if (node == null || Objects.equals(node.getDeleted(), 1)) {
            throw new ServiceException("节点不存在或已删除: id=" + id);
        }

        if (req.getName() != null) {
            node.setName(req.getName());
        }
        if (req.getLevel() != null) {
            node.setLevel(req.getLevel());
        }
        if (req.getParentCode() != null) {
            node.setParentCode(req.getParentCode());
        }
        if (req.getDescription() != null) {
            node.setDescription(req.getDescription());
        }
        if (req.getExamples() != null) {
            node.setExamples(GSON.toJson(req.getExamples()));
        }
        if (req.getCollectionName() != null) {
            node.setCollectionName(req.getCollectionName());
        }
        if (req.getKind() != null) {
            node.setKind(req.getKind());
        }
        if (req.getSortOrder() != null) {
            node.setSortOrder(req.getSortOrder());
        }
        if (req.getEnabled() != null) {
            node.setEnabled(req.getEnabled());
        }
        node.setUpdateBy(UserContext.getUsername());
        this.updateById(node);
    }

    @Override
    public void deleteNode(String id) {
        this.removeById(id);
    }

    @Override
    public int initFromFactory() {
        List<IntentNode> roots = IntentTreeFactory.buildIntentTree();
        List<IntentNode> allNodes = flatten(roots);

        int sort = 0;
        int created = 0;

        for (IntentNode node : allNodes) {
            // 如果已经存在相同 intentCode，就跳过，避免重复初始化
            if (existsByIntentCode(node.getId())) {
                continue;
            }

            IntentNodeCreateRequest nodeCreateRequest = IntentNodeCreateRequest.builder()
                    .kbId(node.getKbId())
                    .intentCode(node.getId())
                    .name(node.getName())
                    .level(mapLevel(node.getLevel()))
                    .parentCode(node.getParentId())
                    .description(node.getDescription())
                    .examples(node.getExamples())
                    .kind(mapKind(node.getKind()))
                    .mcpToolId(node.getMcpToolId())
                    .sortOrder(sort++)
                    .enabled(1)
                    .promptTemplate(node.getPromptTemplate())
                    .promptSnippet(node.getPromptSnippet())
                    .paramPromptTemplate(node.getParamPromptTemplate())
                    .build();
            createNode(nodeCreateRequest);
            created++;
        }

        return created;
    }

    /**
     * 展平树结构：保证父节点在前，子节点在后（先根遍历）
     */
    private List<IntentNode> flatten(List<IntentNode> roots) {
        List<IntentNode> result = new ArrayList<>();
        Deque<IntentNode> stack = new ArrayDeque<>(roots);
        while (!stack.isEmpty()) {
            IntentNode n = stack.pop();
            result.add(n);
            if (n.getChildren() != null && !n.getChildren().isEmpty()) {
                // 为了保证父在前 / 子在后，这里逆序压栈
                List<IntentNode> children = n.getChildren();
                for (int i = children.size() - 1; i >= 0; i--) {
                    stack.push(children.get(i));
                }
            }
        }
        return result;
    }

    /**
     * IntentNode.Level -> Integer（0/1/2）
     */
    private int mapLevel(IntentLevel level) {
        return level.getCode();
    }

    /**
     * IntentKind -> Integer（0=KB, 1=SYSTEM, 2=MCP）
     */
    private int mapKind(IntentKind kind) {
        if (kind == null) {
            return 0; // 默认 KB
        }
        return kind.getCode();
    }

    /**
     * 判断 intentCode 是否已存在，避免重复插入
     */
    private boolean existsByIntentCode(String intentCode) {
        return baseMapper.selectCount(
                new LambdaQueryWrapper<IntentNodeDO>()
                        .eq(IntentNodeDO::getIntentCode, intentCode)
                        .eq(IntentNodeDO::getDeleted, 0)
        ) > 0;
    }
}