package com.nageoffer.ai.ragent.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.google.gson.Gson;
import com.nageoffer.ai.ragent.dao.entity.IntentNodeDO;
import com.nageoffer.ai.ragent.dao.mapper.IntentNodeMapper;
import com.nageoffer.ai.ragent.dto.kb.IntentNodeCreateReqDTO;
import com.nageoffer.ai.ragent.dto.kb.IntentNodeTreeRespDTO;
import com.nageoffer.ai.ragent.dto.kb.IntentNodeUpdateReqDTO;
import com.nageoffer.ai.ragent.enums.IntentKind;
import com.nageoffer.ai.ragent.enums.IntentLevel;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.framework.exception.ServiceException;
import com.nageoffer.ai.ragent.service.IntentTreeService;
import com.nageoffer.ai.ragent.rag.intent.IntentNode;
import com.nageoffer.ai.ragent.rag.intent.IntentTreeFactory;
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

    private static final Gson GSON = new Gson();

    @Override
    public List<IntentNodeTreeRespDTO> getFullTree() {
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
        List<IntentNodeTreeRespDTO> tree = new ArrayList<>();
        for (IntentNodeDO root : roots) {
            tree.add(buildTree(root, parentMap));
        }
        return tree;
    }

    private IntentNodeTreeRespDTO buildTree(IntentNodeDO current,
                                            Map<String, List<IntentNodeDO>> parentMap) {
        IntentNodeTreeRespDTO vo = toVO(current);
        List<IntentNodeDO> children = parentMap.getOrDefault(current.getIntentCode(), Collections.emptyList());
        if (!CollectionUtils.isEmpty(children)) {
            List<IntentNodeTreeRespDTO> childVOs = children.stream()
                    .map(child -> buildTree(child, parentMap))
                    .collect(Collectors.toList());
            vo.setChildren(childVOs);
        }
        return vo;
    }

    private IntentNodeTreeRespDTO toVO(IntentNodeDO entity) {
        IntentNodeTreeRespDTO vo = new IntentNodeTreeRespDTO();
        vo.setId(entity.getId());
        vo.setIntentCode(entity.getIntentCode());
        vo.setName(entity.getName());
        vo.setLevel(entity.getLevel());
        vo.setParentCode(entity.getParentCode());
        vo.setDescription(entity.getDescription());
        vo.setExamples(entity.getExamples());
        vo.setCollectionName(entity.getCollectionName());
        vo.setKind(entity.getKind());
        vo.setSortOrder(entity.getSortOrder());
        vo.setEnabled(entity.getEnabled());
        return vo;
    }

    @Override
    public String createNode(IntentNodeCreateReqDTO req) {
        // 简单重复校验：intentCode 不允许重复
        long count = this.count(new LambdaQueryWrapper<IntentNodeDO>()
                .eq(IntentNodeDO::getIntentCode, req.getIntentCode())
                .eq(IntentNodeDO::getDeleted, 0));
        if (count > 0) {
            throw new ClientException("意图标识已存在: " + req.getIntentCode());
        }

        if (Objects.equals(req.getLevel(), DOMAIN.getCode())
                && Objects.equals(req.getKind(), IntentKind.KB.getCode())
                && StrUtil.isBlank(req.getKbId())) {
            throw new ClientException("Domain类型的RAG检索意图识别时，必须指定目标知识库");
        }

        IntentNodeDO node = new IntentNodeDO();
        node.setIntentCode(req.getIntentCode());
        if (StrUtil.isNotBlank(req.getKbId())) {
            node.setKbId(Long.parseLong(req.getKbId()));
        }
        node.setName(req.getName());
        node.setLevel(req.getLevel());
        node.setParentCode(req.getParentCode());
        node.setDescription(req.getDescription());
        node.setExamples(req.getExamples() == null ? null : GSON.toJson(req.getExamples()));
        node.setCollectionName(req.getCollectionName());
        node.setKind(req.getKind() == null ? 0 : req.getKind());
        node.setSortOrder(req.getSortOrder() == null ? 0 : req.getSortOrder());
        node.setEnabled(req.getEnabled() == null ? 1 : req.getEnabled());
        node.setCreateBy("");
        node.setUpdateBy("");
        node.setPromptSnippet(req.getPromptSnippet());
        node.setPromptTemplate(req.getPromptTemplate());
        node.setDeleted(0);

        this.save(node);
        return String.valueOf(node.getId());
    }

    @Override
    public void updateNode(Long id, IntentNodeUpdateReqDTO req) {
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
        node.setUpdateBy("");
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

            IntentNodeCreateReqDTO dto = new IntentNodeCreateReqDTO();
            dto.setKbId(node.getKbId());
            dto.setIntentCode(node.getId());
            dto.setName(node.getName());
            dto.setLevel(mapLevel(node.getLevel()));
            dto.setParentCode(node.getParentId());
            dto.setDescription(node.getDescription());
            dto.setExamples(node.getExamples());
            dto.setCollectionName(node.getCollectionName());
            dto.setKind(mapKind(node.getKind()));
            dto.setSortOrder(sort++);
            dto.setEnabled(1);
            dto.setPromptTemplate(node.getPromptTemplate());
            dto.setPromptSnippet(node.getPromptSnippet());
            createNode(dto);
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
     * IntentNode.Level -> Integer（1/2/3）
     */
    private int mapLevel(IntentLevel level) {
        return level.getCode();
    }

    /**
     * IntentKind -> Integer（0=RAG,1=SYSTEM）
     */
    private int mapKind(IntentKind kind) {
        if (kind == null) {
            return 0; // 默认 RAG
        }
        return kind == IntentKind.SYSTEM ? 1 : 0;
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