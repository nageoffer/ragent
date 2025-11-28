package com.nageoffer.ai.ragent.rag.Intent;

import com.nageoffer.ai.ragent.service.rag.embedding.EmbeddingService;
import com.nageoffer.ai.ragent.service.rag.intent.IntentNode;
import com.nageoffer.ai.ragent.service.rag.intent.IntentTreeFactory;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class VectorIntentClassifier {

    private final EmbeddingService embeddingService;

    /**
     * 整棵树所有节点
     */
    private List<IntentNode> allNodes;

    /**
     * 只包含“最终节点”（叶子），也就是挂知识库的节点
     */
    private List<IntentNode> targetNodes;

    /**
     * 根节点：Domain 层（目前没在分类里用到，只是保留）
     */
    private List<IntentNode> rootNodes;

    @PostConstruct
    public void init() {
        // 1. 构建意图树
        this.rootNodes = IntentTreeFactory.buildIntentTree();
        this.allNodes = flatten(rootNodes);

        // 2. 过滤出“最终分类节点”（叶子）
        this.targetNodes = allNodes.stream()
                .filter(IntentNode::isLeaf)
                .collect(Collectors.toList());

        // 3. 为所有节点预计算向量（也可以只算 targetNodes，看你后面是否想用到）
        for (IntentNode node : allNodes) {
            String text = buildNodeText(node);
            float[] vec = toArray(embeddingService.embed(text));
            node.setEmbedding(vec);
        }

        log.info("[VectorIntentClassifier] init done, allNodes={}, leafTargets={}",
                allNodes.size(), targetNodes.size());
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

    private String buildNodeText(IntentNode node) {
        StringBuilder sb = new StringBuilder();
        sb.append("路径: ").append(node.getFullPath()).append("\n");
        sb.append("说明: ").append(node.getDescription()).append("\n");
        if (node.getExamples() != null && !node.getExamples().isEmpty()) {
            sb.append("示例问题: ");
            for (String ex : node.getExamples()) {
                sb.append("【").append(ex).append("】");
            }
        }
        return sb.toString();
    }

    private float[] toArray(List<Float> list) {
        float[] arr = new float[list.size()];
        for (int i = 0; i < list.size(); i++) {
            arr[i] = list.get(i);
        }
        return arr;
    }

    private double cosine(float[] a, float[] b) {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na == 0 || nb == 0) return 0;
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    /**
     * 对所有“最终分类节点（叶子）”打分，按相似度从高到低排序返回。
     * - 不返回父节点，不做 domain/category/topic 的合并；
     * - 每一个 NodeScore 就是一个“可以直接挂知识库”的分类目标。
     */
    public List<NodeScore> classifyTargets(String question) {
        float[] qVec = toArray(embeddingService.embed(question));

        return targetNodes.stream()
                .map(n -> new NodeScore(n, cosine(qVec, n.getEmbedding())))
                .sorted(Comparator.comparingDouble(NodeScore::score).reversed())
                .toList();
    }

    /**
     * 方便使用的小工具：
     * - 只取前 topN 个，并过滤掉低于 minScore 的分类
     * - 如果返回列表为空，你可以选择“直接不走 RAG 检索”
     */
    public List<NodeScore> topKAboveThreshold(String question, int topN, double minScore) {
        List<NodeScore> all = classifyTargets(question);
        return all.stream()
                .filter(ns -> ns.score() >= minScore)
                .limit(topN)
                .toList();
    }

    /**
     * 每个最终分类节点对应一个得分（相似度）。
     * 由上层业务决定：
     * - 对哪些分类/知识库继续做向量检索；
     * - 如果 maxScore 很低，是否直接不走向量检索。
     */
    public record NodeScore(
            IntentNode node,
            double score
    ) {
    }
}

