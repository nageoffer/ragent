package com.nageoffer.ai.ragent.rag.intent;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.google.gson.*;
import com.nageoffer.ai.ragent.dao.entity.IntentNodeDO;
import com.nageoffer.ai.ragent.dao.mapper.IntentNodeMapper;
import com.nageoffer.ai.ragent.rag.chat.LLMService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

import static com.nageoffer.ai.ragent.constant.RAGConstant.INTENT_CLASSIFIER_PROMPT;

/**
 * 基于大模型（LLM）的 Tree 意图分类器：
 * - 使用 domain / category / topic 三层 Tree
 * - 只对【叶子节点】做意图分类
 * - 由 LLM 直接输出每个分类的匹配分数 score（0~1）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LLMTreeIntentClassifier {

    private final LLMService llmService;
    private final IntentNodeMapper intentNodeMapper;

    /**
     * 整棵树所有节点（可选，用于调试）
     */
    private List<IntentNode> allNodes;

    /**
     * 只包含“最终分类节点”（叶子），即真正挂知识库 / Milvus Collection 的节点
     */
    private List<IntentNode> leafNodes;

    /**
     * id -> node 映射，方便 LLM 结果反查
     */
    private Map<String, IntentNode> id2Node;

    @PostConstruct
    public void init() {
        // 1. 构建 Tree
        List<IntentNode> roots = loadIntentTreeFromDB();
        this.allNodes = flatten(roots);

        // 2. 提取叶子节点（最终分类）
        this.leafNodes = allNodes.stream()
                .filter(IntentNode::isLeaf)
                .collect(Collectors.toList());

        this.id2Node = allNodes.stream()
                .collect(Collectors.toMap(IntentNode::getId, n -> n));

        log.info("[LlmTreeIntentClassifier] init done, allNodes={}, leafNodes={}",
                allNodes.size(), leafNodes.size());
    }

    private List<IntentNode> flatten(List<IntentNode> roots) {
        List<IntentNode> result = new ArrayList<>();
        Deque<IntentNode> stack = new ArrayDeque<>(roots);
        while (!stack.isEmpty()) {
            IntentNode n = stack.pop();
            result.add(n);
            if (n.getChildren() != null) {
                for (IntentNode child : n.getChildren()) {
                    stack.push(child);
                }
            }
        }
        return result;
    }

    /**
     * 对所有“叶子分类节点”做意图识别，由 LLM 输出每个分类的 score
     * - 返回结果已按 score 从高到低排序
     */
    public List<NodeScore> classifyTargets(String question) {
        String prompt = buildPrompt(question);
        String raw = llmService.chat(prompt);  // 走你现有的 LLMService

        try {
            JsonElement root = JsonParser.parseString(raw.trim());

            JsonArray arr;
            if (root.isJsonArray()) {
                arr = root.getAsJsonArray();
            } else if (root.isJsonObject() && root.getAsJsonObject().has("results")) {
                // 容错：如果模型外面又包了一层 { "results": [...] }
                arr = root.getAsJsonObject().getAsJsonArray("results");
            } else {
                log.warn("[LlmTreeIntentClassifier] unexpected LLM json: {}", raw);
                return List.of();
            }

            List<NodeScore> scores = new ArrayList<>();
            for (JsonElement el : arr) {
                if (!el.isJsonObject()) continue;
                JsonObject obj = el.getAsJsonObject();

                if (!obj.has("id") || !obj.has("score")) continue;

                String id = obj.get("id").getAsString();
                double score = obj.get("score").getAsDouble();

                IntentNode node = id2Node.get(id);
                if (node == null) {
                    log.warn("[LlmTreeIntentClassifier] LLM returned unknown id: {}", id);
                    continue;
                }

                scores.add(new NodeScore(node, score));
            }

            // 降序排序
            scores.sort(Comparator.comparingDouble(NodeScore::getScore).reversed());

            log.info("意图识别树如下所示:\n{}",
                    JSONUtil.toJsonPrettyStr(
                            scores.stream().map(each -> {
                                IntentNode node = each.getNode();
                                node.setChildren(null);
                                return each;
                            }).collect(Collectors.toList())
                    )
            );
            return scores;
        } catch (Exception e) {
            log.warn("[LlmTreeIntentClassifier] parse LLM response error, raw={}", raw, e);
            return List.of();
        }
    }

    /**
     * 方便使用：
     * - 只取前 topN
     * - 过滤掉 score < minScore 的分类
     */
    public List<NodeScore> topKAboveThreshold(String question, int topN, double minScore) {
        return classifyTargets(question).stream()
                .filter(ns -> ns.getScore() >= minScore)
                .limit(topN)
                .toList();
    }

    /**
     * 构造给 LLM 的 Prompt：
     * - 列出所有【叶子节点】的 id / 路径 / 描述 / 示例问题
     * - 要求 LLM 只在这些 id 中选择，输出 JSON 数组：[{"id": "...", "score": 0.9, "reason": "..."}]
     * - 特别强调：如果问题里只提到 “OA系统”，不要选 “保险系统” 的分类
     */
    private String buildPrompt(String question) {
        StringBuilder sb = new StringBuilder();
        for (IntentNode node : allNodes) {
            sb.append("- id=").append(node.getId()).append("\n");
            sb.append("  path=").append(node.getFullPath()).append("\n");
            sb.append("  description=").append(node.getDescription()).append("\n");
            if (node.getExamples() != null && !node.getExamples().isEmpty()) {
                sb.append("  examples=");
                sb.append(String.join(" / ", node.getExamples()));
                sb.append("\n");
            }
            sb.append("\n");
        }

        return INTENT_CLASSIFIER_PROMPT.formatted(sb.toString(), question);
    }

    private List<IntentNode> loadIntentTreeFromDB() {
        // 1. 查出所有未删除节点（扁平结构）
        List<IntentNodeDO> intentNodeDOList = intentNodeMapper.selectList(
                Wrappers.lambdaQuery(IntentNodeDO.class)
                        .eq(IntentNodeDO::getDeleted, 0)
        );

        if (intentNodeDOList.isEmpty()) {
            return List.of();
        }

        // 2. DO -> IntentNode（第一遍：先把所有节点建出来，放到 map 里）
        Map<String, IntentNode> id2Node = new HashMap<>();
        for (IntentNodeDO each : intentNodeDOList) {
            IntentNode node = BeanUtil.toBean(each, IntentNode.class);
            // 数据库中的 code 映射到 IntentNode 的 id/parentId
            node.setId(each.getIntentCode());
            node.setParentId(each.getParentCode());
            node.setMcpToolId(each.getMcpToolId());
            // 确保 children 不为 null（避免后面 add NPE）
            if (node.getChildren() == null) {
                node.setChildren(new ArrayList<>());
            }
            id2Node.put(node.getId(), node);
        }

        // 3. 第二遍：根据 parentId 组装 parent -> children
        List<IntentNode> roots = new ArrayList<>();
        for (IntentNode node : id2Node.values()) {
            String parentId = node.getParentId();
            if (parentId == null || parentId.isBlank()) {
                // 没有 parentId，当作根节点
                roots.add(node);
                continue;
            }

            IntentNode parent = id2Node.get(parentId);
            if (parent == null) {
                // 找不到父节点，兜底也当作根节点，避免节点丢失
                roots.add(node);
                continue;
            }

            // 追加到父节点的 children
            if (parent.getChildren() == null) {
                parent.setChildren(new ArrayList<>());
            }
            parent.getChildren().add(node);
        }

        // 4. 填充 fullPath（跟你原来的 fillFullPath 一样的逻辑）
        fillFullPath(roots, null);

        return roots;
    }

    /**
     * 填充 fullPath 字段，效果类似：
     * - 集团信息化
     * - 集团信息化 > 人事
     * - 业务系统 > OA系统 > 系统介绍
     */
    private void fillFullPath(List<IntentNode> nodes, IntentNode parent) {
        if (nodes == null) return;

        for (IntentNode node : nodes) {
            if (parent == null) {
                node.setFullPath(node.getName());
            } else {
                node.setFullPath(parent.getFullPath() + " > " + node.getName());
            }

            if (node.getChildren() != null && !node.getChildren().isEmpty()) {
                fillFullPath(node.getChildren(), node);
            }
        }
    }
}
