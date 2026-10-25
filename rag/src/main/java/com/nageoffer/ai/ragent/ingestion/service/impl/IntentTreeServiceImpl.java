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
import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.google.gson.Gson;
import com.mzt.logapi.starter.annotation.LogRecord;
import com.nageoffer.ai.ragent.audit.constant.BizChangeBizType;
import com.nageoffer.ai.ragent.audit.constant.BizChangeOperationType;
import com.nageoffer.ai.ragent.audit.support.BizChangeLogContext;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeBaseDO;
import com.nageoffer.ai.ragent.rag.controller.request.IntentNodeCreateRequest;
import com.nageoffer.ai.ragent.rag.controller.request.IntentNodeUpdateRequest;
import com.nageoffer.ai.ragent.rag.controller.vo.IntentNodeTreeVO;
import com.nageoffer.ai.ragent.rag.dao.entity.IntentNodeDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.IntentNodeMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.nageoffer.ai.ragent.rag.enums.IntentKind;
import com.nageoffer.ai.ragent.rag.enums.IntentLevel;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.framework.exception.ServiceException;
import com.nageoffer.ai.ragent.rag.core.intent.IntentTreeCacheManager;
import com.nageoffer.ai.ragent.ingestion.service.IntentTreeService;
import lombok.RequiredArgsConstructor;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class IntentTreeServiceImpl implements IntentTreeService {

    private final IntentNodeMapper intentNodeMapper;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final IntentTreeCacheManager intentTreeCacheManager;
    private final BizChangeLogContext bizChangeLogContext;

    private static final Gson GSON = new Gson();

    @Override
    public List<IntentNodeTreeVO> getFullTree() {
        List<IntentNodeDO> list = intentNodeMapper.selectList(new LambdaQueryWrapper<IntentNodeDO>()
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
        result.setCollectionNames(effectiveCollectionNames(current));
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
    @LogRecord(
            success = "创建意图节点：{{#requestParam.name}}",
            fail = "创建意图节点失败：{{#_errorMsg}}",
            type = BizChangeBizType.INTENT_TREE,
            subType = BizChangeOperationType.CREATE,
            bizNo = BizChangeLogContext.BIZ_ID_EXPRESSION,
            extra = BizChangeLogContext.SNAPSHOT_EXPRESSION,
            condition = BizChangeLogContext.RECORD_CONDITION
    )
    public String createNode(IntentNodeCreateRequest requestParam) {
        // 简单重复校验：intentCode 不允许重复
        long count = intentNodeMapper.selectCount(new LambdaQueryWrapper<IntentNodeDO>()
                .eq(IntentNodeDO::getIntentCode, requestParam.getIntentCode())
                .eq(IntentNodeDO::getDeleted, 0));
        if (count > 0) {
            throw new ClientException("意图标识已存在: " + requestParam.getIntentCode());
        }

        int kind = requestParam.getKind() == null ? IntentKind.KB.getCode() : requestParam.getKind();
        if (Objects.equals(kind, IntentKind.MCP.getCode())) {
            requireUniqueMcpToolId(StrUtil.trim(requestParam.getMcpToolId()), null);
        }
        CollectionBinding collectionBinding = Objects.equals(kind, IntentKind.KB.getCode())
                ? resolveCreateCollectionBinding(requestParam)
                : CollectionBinding.empty();
        if (Objects.equals(requestParam.getLevel(), IntentLevel.TOPIC.getCode())
                && Objects.equals(kind, IntentKind.KB.getCode())
                && collectionBinding.collectionNames().isEmpty()) {
            throw new ClientException("TOPIC级别的RAG检索节点必须至少指定一个目标知识库");
        }

        IntentNodeDO node = IntentNodeDO.builder()
                .intentCode(requestParam.getIntentCode())
                .kbId(collectionBinding.primaryKbId())
                .collectionName(firstOrNull(collectionBinding.collectionNames()))
                .collectionNames(collectionBinding.collectionNames())
                .name(requestParam.getName())
                .level(requestParam.getLevel())
                .parentCode(requestParam.getParentCode())
                .description(requestParam.getDescription())
                .mcpToolId(Objects.equals(kind, IntentKind.MCP.getCode())
                        ? StrUtil.trim(requestParam.getMcpToolId()) : null)
                .requireConfirm(normalizeRequireConfirm(requestParam.getRequireConfirm(), kind))
                .examples(
                        requestParam.getExamples() == null ? null : GSON.toJson(requestParam.getExamples())
                )
                .topK(normalizeTopK(requestParam.getTopK()))
                .kind(kind)
                .sortOrder(
                        requestParam.getSortOrder() == null ? 0 : requestParam.getSortOrder()
                )
                .enabled(
                        requestParam.getEnabled() == null ? 1 : requestParam.getEnabled()
                )
                .createBy(UserContext.getUsername())
                .updateBy(UserContext.getUsername())
                .promptSnippet(Objects.equals(kind, IntentKind.MCP.getCode()) ? null : requestParam.getPromptSnippet())
                .promptTemplate(Objects.equals(kind, IntentKind.MCP.getCode()) ? null : requestParam.getPromptTemplate())
                .deleted(0)
                .build();

        intentNodeMapper.insert(node);

        // 清除Redis缓存，下次读取时会重新从数据库加载
        intentTreeCacheManager.clearIntentTreeCache();

        bizChangeLogContext.put(String.valueOf(node.getId()), null, node);
        return String.valueOf(node.getId());
    }

    @Override
    @LogRecord(
            success = "更新意图节点：{{#id}}",
            fail = "更新意图节点失败：{{#_errorMsg}}",
            type = BizChangeBizType.INTENT_TREE,
            subType = BizChangeOperationType.UPDATE,
            bizNo = "{{#id}}",
            extra = BizChangeLogContext.SNAPSHOT_EXPRESSION,
            condition = BizChangeLogContext.RECORD_CONDITION
    )
    public void updateNode(String id, IntentNodeUpdateRequest req) {
        IntentNodeDO node = intentNodeMapper.selectById(id);
        if (node == null || Objects.equals(node.getDeleted(), 1)) {
            throw new ServiceException("节点不存在或已删除: id=" + id);
        }
        IntentNodeDO before = BeanUtil.copyProperties(node, IntentNodeDO.class);

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
        if (req.getMcpToolId() != null) {
            node.setMcpToolId(StrUtil.trim(req.getMcpToolId()));
        }

        CollectionBinding collectionBinding = null;
        if (req.getCollectionNames() != null) {
            collectionBinding = resolveCollectionBinding(req.getCollectionNames());
        } else if (req.getCollectionName() != null) {
            collectionBinding = resolveCollectionBinding(
                    StrUtil.isBlank(req.getCollectionName()) ? List.of() : List.of(req.getCollectionName())
            );
        }
        if (collectionBinding != null) {
            applyCollectionBinding(node, collectionBinding);
        }
        if (req.getTopK() != null) {
            node.setTopK(normalizeTopK(req.getTopK()));
        }
        if (req.getKind() != null) {
            node.setKind(req.getKind());
        }
        if (req.getRequireConfirm() != null) {
            node.setRequireConfirm(normalizeRequireConfirm(req.getRequireConfirm(), node.getKind()));
        }
        if (Objects.equals(node.getKind(), IntentKind.MCP.getCode())) {
            requireUniqueMcpToolId(node.getMcpToolId(), id);
        } else {
            node.setMcpToolId(null);
            node.setRequireConfirm(0);
        }
        if (!Objects.equals(node.getKind(), IntentKind.KB.getCode())) {
            applyCollectionBinding(node, CollectionBinding.empty());
        }
        if (Objects.equals(node.getKind(), IntentKind.KB.getCode())
                && Objects.equals(node.getLevel(), IntentLevel.TOPIC.getCode())
                && effectiveCollectionNames(node).isEmpty()) {
            throw new ClientException("TOPIC级别的RAG检索节点必须至少指定一个目标知识库");
        }
        if (req.getSortOrder() != null) {
            node.setSortOrder(req.getSortOrder());
        }
        if (req.getEnabled() != null) {
            node.setEnabled(req.getEnabled());
        }
        if (!Objects.equals(node.getKind(), IntentKind.MCP.getCode())) {
            if (req.getPromptSnippet() != null) {
                node.setPromptSnippet(req.getPromptSnippet());
            }
            if (req.getPromptTemplate() != null) {
                node.setPromptTemplate(req.getPromptTemplate());
            }
        }
        node.setUpdateBy(UserContext.getUsername());
        intentNodeMapper.updateById(node);

        // 清除Redis缓存，下次读取时会重新从数据库加载
        intentTreeCacheManager.clearIntentTreeCache();
        bizChangeLogContext.put(id, before, intentNodeMapper.selectById(id));
    }

    @Override
    @LogRecord(
            success = "删除意图节点：{{#id}}",
            fail = "删除意图节点失败：{{#_errorMsg}}",
            type = BizChangeBizType.INTENT_TREE,
            subType = BizChangeOperationType.DELETE,
            bizNo = "{{#id}}",
            extra = BizChangeLogContext.SNAPSHOT_EXPRESSION,
            condition = BizChangeLogContext.RECORD_CONDITION
    )
    public void deleteNode(String id) {
        IntentNodeDO node = intentNodeMapper.selectById(id);
        if (node == null || Objects.equals(node.getDeleted(), 1)) {
            throw new ServiceException("节点不存在或已删除: id=" + id);
        }
        // 子节点不随父节点删除：管理端树只从 parentCode 为空的节点往下递归，子节点会从树里消失，
        // 而运行时分类器对找不到父节点的节点兜底当根节点，导致删除后仍参与意图识别
        List<IntentNodeDO> children = intentNodeMapper.selectList(new LambdaQueryWrapper<IntentNodeDO>()
                .eq(IntentNodeDO::getParentCode, node.getIntentCode())
                .eq(IntentNodeDO::getDeleted, 0));
        if (CollectionUtils.isNotEmpty(children)) {
            throw new ClientException(String.format(
                    "删除失败：节点 [%s] 存在子节点（如：%s），请先删除子节点，或勾选完整子树后批量删除",
                    node.getName(),
                    summarizeNodeNames(children)
            ));
        }
        IntentNodeDO before = BeanUtil.copyProperties(node, IntentNodeDO.class);
        intentNodeMapper.deleteById(id);

        // 清除Redis缓存，下次读取时会重新从数据库加载
        intentTreeCacheManager.clearIntentTreeCache();
        bizChangeLogContext.put(id, before, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @LogRecord(
            success = "批量启用意图节点",
            fail = "批量启用意图节点失败：{{#_errorMsg}}",
            type = BizChangeBizType.INTENT_TREE,
            subType = BizChangeOperationType.ENABLE,
            bizNo = BizChangeLogContext.BIZ_ID_EXPRESSION,
            extra = BizChangeLogContext.SNAPSHOT_EXPRESSION,
            condition = BizChangeLogContext.RECORD_CONDITION
    )
    public void batchEnableNodes(List<String> ids) {
        List<IntentNodeDO> targetNodes = listAndValidateTargetNodes(ids);
        List<IntentNodeDO> before = copyNodes(targetNodes);
        String operator = UserContext.getUsername();
        targetNodes.forEach(node -> {
            node.setEnabled(1);
            node.setUpdateBy(operator);
        });
        intentNodeMapper.updateById(targetNodes);
        intentTreeCacheManager.clearIntentTreeCache();
        bizChangeLogContext.put("BATCH", before, targetNodes);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @LogRecord(
            success = "批量禁用意图节点",
            fail = "批量禁用意图节点失败：{{#_errorMsg}}",
            type = BizChangeBizType.INTENT_TREE,
            subType = BizChangeOperationType.DISABLE,
            bizNo = BizChangeLogContext.BIZ_ID_EXPRESSION,
            extra = BizChangeLogContext.SNAPSHOT_EXPRESSION,
            condition = BizChangeLogContext.RECORD_CONDITION
    )
    public void batchDisableNodes(List<String> ids) {
        List<IntentNodeDO> targetNodes = listAndValidateTargetNodes(ids);
        List<IntentNodeDO> before = copyNodes(targetNodes);
        List<IntentNodeDO> allActiveNodes = listActiveNodes();
        Map<String, List<IntentNodeDO>> childrenMap = buildChildrenMap(allActiveNodes);
        Set<String> targetIdSet = targetNodes.stream().map(IntentNodeDO::getId).collect(Collectors.toSet());
        for (IntentNodeDO targetNode : targetNodes) {
            List<IntentNodeDO> descendants = collectDescendants(targetNode.getIntentCode(), childrenMap);
            List<IntentNodeDO> enabledButNotSelected = descendants.stream()
                    .filter(item -> Objects.equals(item.getEnabled(), 1) && !targetIdSet.contains(item.getId()))
                    .collect(Collectors.toList());
            if (CollectionUtils.isNotEmpty(enabledButNotSelected)) {
                throw new ClientException(
                        String.format(
                                "批量停用失败：节点 [%s] 存在已启用的子节点未包含在本次操作中（如：%s），请先选择全量子节点",
                                targetNode.getName(),
                                summarizeNodeNames(enabledButNotSelected)
                        )
                );
            }
        }
        String operator = UserContext.getUsername();
        targetNodes.forEach(node -> {
            node.setEnabled(0);
            node.setUpdateBy(operator);
        });
        intentNodeMapper.updateById(targetNodes);
        intentTreeCacheManager.clearIntentTreeCache();
        bizChangeLogContext.put("BATCH", before, targetNodes);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @LogRecord(
            success = "批量删除意图节点",
            fail = "批量删除意图节点失败：{{#_errorMsg}}",
            type = BizChangeBizType.INTENT_TREE,
            subType = BizChangeOperationType.DELETE,
            bizNo = BizChangeLogContext.BIZ_ID_EXPRESSION,
            extra = BizChangeLogContext.SNAPSHOT_EXPRESSION,
            condition = BizChangeLogContext.RECORD_CONDITION
    )
    public void batchDeleteNodes(List<String> ids) {
        List<IntentNodeDO> targetNodes = listAndValidateTargetNodes(ids);
        List<IntentNodeDO> before = copyNodes(targetNodes);
        List<IntentNodeDO> allActiveNodes = listActiveNodes();
        Map<String, List<IntentNodeDO>> childrenMap = buildChildrenMap(allActiveNodes);
        Set<String> targetIdSet = targetNodes.stream().map(IntentNodeDO::getId).collect(Collectors.toSet());
        for (IntentNodeDO targetNode : targetNodes) {
            List<IntentNodeDO> descendants = collectDescendants(targetNode.getIntentCode(), childrenMap);
            List<IntentNodeDO> notSelectedDescendants = descendants.stream()
                    .filter(item -> !targetIdSet.contains(item.getId()))
                    .collect(Collectors.toList());
            if (CollectionUtils.isNotEmpty(notSelectedDescendants)) {
                List<IntentNodeDO> enabledDescendants = notSelectedDescendants.stream()
                        .filter(item -> Objects.equals(item.getEnabled(), 1))
                        .collect(Collectors.toList());
                if (CollectionUtils.isNotEmpty(enabledDescendants)) {
                    throw new ClientException(
                            String.format(
                                    "批量删除失败：节点 [%s] 存在已启用的子节点未包含在本次操作中（如：%s），请先选择全量子节点",
                                    targetNode.getName(),
                                    summarizeNodeNames(enabledDescendants)
                            )
                    );
                }
                throw new ClientException(
                        String.format(
                                "批量删除失败：节点 [%s] 未包含全量子节点（如：%s），请先勾选完整子树后再删除",
                                targetNode.getName(),
                                summarizeNodeNames(notSelectedDescendants)
                        )
                );
            }
        }
        intentNodeMapper.deleteByIds(targetIdSet);
        intentTreeCacheManager.clearIntentTreeCache();
        bizChangeLogContext.put("BATCH", before, null);
    }

    /**
     * 规范化节点级 TopK：
     * - null 表示未配置，回退全局默认
     * - 仅允许正整数
     */
    private Integer normalizeTopK(Integer topK) {
        if (topK == null) {
            return null;
        }
        if (topK <= 0) {
            throw new ClientException("节点级 TopK 必须大于 0");
        }
        return topK;
    }

    /**
     * 一个 MCP 工具只允许一个节点：Agent 按工具名装配，节点上的确认开关就是该工具的开关，不做多节点合并
     *
     * @param excludeId 更新时排除自身
     */
    private void requireUniqueMcpToolId(String toolId, String excludeId) {
        if (StrUtil.isBlank(toolId)) {
            return;
        }
        long count = intentNodeMapper.selectCount(new LambdaQueryWrapper<IntentNodeDO>()
                .eq(IntentNodeDO::getKind, IntentKind.MCP.getCode())
                .eq(IntentNodeDO::getMcpToolId, toolId)
                .eq(IntentNodeDO::getDeleted, 0)
                .ne(excludeId != null, IntentNodeDO::getId, excludeId));
        if (count > 0) {
            throw new ClientException("MCP 工具已被其他节点关联，一个工具只允许配置一个节点: " + toolId);
        }
    }

    private Integer normalizeRequireConfirm(Integer requireConfirm, Integer kind) {
        if (!Objects.equals(kind, IntentKind.MCP.getCode())) {
            return 0;
        }
        return Objects.equals(requireConfirm, 1) ? 1 : 0;
    }

    /**
     * 创建接口兼容旧的 kbId，同时将新的 collectionNames 作为明确的优先输入
     */
    private CollectionBinding resolveCreateCollectionBinding(IntentNodeCreateRequest request) {
        if (request.getCollectionNames() != null) {
            return resolveCollectionBinding(request.getCollectionNames());
        }
        if (StrUtil.isBlank(request.getKbId())) {
            return CollectionBinding.empty();
        }
        KnowledgeBaseDO knowledgeBase = knowledgeBaseMapper.selectById(request.getKbId());
        if (knowledgeBase == null || Objects.equals(knowledgeBase.getDeleted(), 1)) {
            throw new ClientException("知识库不存在或已删除: " + request.getKbId());
        }
        return new CollectionBinding(List.of(knowledgeBase.getCollectionName()), knowledgeBase.getId());
    }

    /**
     * 校验 Collection 均来自有效知识库，并保持前端选择顺序
     */
    private CollectionBinding resolveCollectionBinding(List<String> requestedCollectionNames) {
        List<String> collectionNames = normalizeCollectionNames(requestedCollectionNames);
        if (collectionNames.isEmpty()) {
            return CollectionBinding.empty();
        }

        List<KnowledgeBaseDO> knowledgeBases = knowledgeBaseMapper.selectList(
                new LambdaQueryWrapper<KnowledgeBaseDO>()
                        .in(KnowledgeBaseDO::getCollectionName, collectionNames)
                        .eq(KnowledgeBaseDO::getDeleted, 0)
        );
        Map<String, KnowledgeBaseDO> byCollectionName = knowledgeBases.stream()
                .collect(Collectors.toMap(KnowledgeBaseDO::getCollectionName, item -> item));
        List<String> missing = collectionNames.stream()
                .filter(collectionName -> !byCollectionName.containsKey(collectionName))
                .toList();
        if (!missing.isEmpty()) {
            throw new ClientException("知识库 Collection 不存在或已删除: " + missing);
        }

        return new CollectionBinding(
                collectionNames,
                byCollectionName.get(collectionNames.get(0)).getId()
        );
    }

    private List<String> normalizeCollectionNames(List<String> collectionNames) {
        if (collectionNames == null) {
            return List.of();
        }
        LinkedHashSet<String> normalized = collectionNames.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(StrUtil::isNotBlank)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return List.copyOf(normalized);
    }

    private void applyCollectionBinding(IntentNodeDO node, CollectionBinding binding) {
        node.setCollectionNames(binding.collectionNames());
        node.setCollectionName(firstOrNull(binding.collectionNames()));
        node.setKbId(binding.primaryKbId());
    }

    private List<String> effectiveCollectionNames(IntentNodeDO node) {
        if (CollectionUtils.isNotEmpty(node.getCollectionNames())) {
            return normalizeCollectionNames(node.getCollectionNames());
        }
        if (StrUtil.isNotBlank(node.getCollectionName())) {
            return List.of(node.getCollectionName().trim());
        }
        return List.of();
    }

    private String firstOrNull(List<String> values) {
        return CollectionUtils.isEmpty(values) ? null : values.get(0);
    }

    private record CollectionBinding(List<String> collectionNames, String primaryKbId) {

        private static CollectionBinding empty() {
            return new CollectionBinding(List.of(), null);
        }
    }

    private List<IntentNodeDO> listAndValidateTargetNodes(List<String> ids) {
        Assert.notEmpty(ids, () -> new ClientException("请至少选择一个节点"));
        List<String> normalizedIds = ids.stream().filter(Objects::nonNull).distinct().collect(Collectors.toList());
        Assert.notEmpty(normalizedIds, () -> new ClientException("节点ID不能为空"));
        List<IntentNodeDO> targetNodes = intentNodeMapper.selectList(new LambdaQueryWrapper<IntentNodeDO>()
                .in(IntentNodeDO::getId, normalizedIds)
                .eq(IntentNodeDO::getDeleted, 0));
        if (targetNodes.size() != normalizedIds.size()) {
            Set<String> existingIds = targetNodes.stream().map(IntentNodeDO::getId).collect(Collectors.toSet());
            List<String> missingIds = normalizedIds.stream()
                    .filter(id -> !existingIds.contains(id))
                    .limit(5)
                    .toList();
            throw new ClientException("节点不存在或已删除: " + missingIds);
        }
        return targetNodes;
    }

    private List<IntentNodeDO> listActiveNodes() {
        return intentNodeMapper.selectList(new LambdaQueryWrapper<IntentNodeDO>()
                .eq(IntentNodeDO::getDeleted, 0));
    }

    private Map<String, List<IntentNodeDO>> buildChildrenMap(List<IntentNodeDO> nodes) {
        return nodes.stream().collect(Collectors.groupingBy(node -> {
            String parentCode = node.getParentCode();
            return parentCode == null ? "ROOT" : parentCode;
        }));
    }

    private List<IntentNodeDO> collectDescendants(String intentCode, Map<String, List<IntentNodeDO>> childrenMap) {
        if (StrUtil.isBlank(intentCode)) {
            return Collections.emptyList();
        }
        List<IntentNodeDO> result = new ArrayList<>();
        Deque<IntentNodeDO> stack = new ArrayDeque<>(
                childrenMap.getOrDefault(intentCode, Collections.emptyList())
        );
        while (!stack.isEmpty()) {
            IntentNodeDO current = stack.pop();
            result.add(current);
            List<IntentNodeDO> children = childrenMap.getOrDefault(current.getIntentCode(), Collections.emptyList());
            for (int i = children.size() - 1; i >= 0; i--) {
                stack.push(children.get(i));
            }
        }
        return result;
    }

    private String summarizeNodeNames(List<IntentNodeDO> nodes) {
        return nodes.stream()
                .limit(3)
                .map(item -> StrUtil.blankToDefault(item.getName(), item.getIntentCode()))
                .collect(Collectors.joining("、"));
    }

    private List<IntentNodeDO> copyNodes(List<IntentNodeDO> nodes) {
        return nodes.stream()
                .map(node -> BeanUtil.copyProperties(node, IntentNodeDO.class))
                .toList();
    }
}
