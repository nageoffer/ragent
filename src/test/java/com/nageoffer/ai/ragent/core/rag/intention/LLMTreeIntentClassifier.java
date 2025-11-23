package com.nageoffer.ai.ragent.core.rag.intention;

import com.google.gson.*;
import com.nageoffer.ai.ragent.core.service.rag.chat.LLMService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

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

    private final Gson gson = new GsonBuilder()
            .disableHtmlEscaping()
            .create();

    @PostConstruct
    public void init() {
        // 1. 构建 Tree
        List<IntentNode> roots = IntentTreeFactory.buildIntentTree();
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

        sb.append("""
                你是一个企业内部知识库的【意图分类助手】。
                
                【任务说明】
                1. 下面会给出若干“最终分类节点”（叶子节点），它们都挂接了各自的知识库/文档集合。
                2. 每个节点包含：
                   - id：唯一标识
                   - path：在分类树中的完整路径（domain / category / topic）
                   - description：该分类覆盖的知识范围
                   - examples：该分类下典型的用户提问
                3. 你的任务是：根据【用户问题】，判断它与哪些叶子分类最相关。
                4. 允许一个问题对应多个分类。
                5. 对每个你认为相关的分类，给出一个 0~1 之间的匹配分数 score，score 越高表示越相关。
                6. 对明显无关的分类，不要返回。
                
                【特别重要的规则】
                - 如果用户问题中明确提到了某个具体系统名称（例如：“OA系统”、“保险系统”），优先只在该系统对应的分类下进行选择。
                - 例如：问题中只提到“OA系统”，不要选择“保险系统”的分类，即使它们都包含“数据安全”等相似词。
                - 只有当问题非常明确是在比较多个系统时，才可以同时选择多个系统的分类。
                - 不要仅因为关键词相似（例如都出现“数据安全”、“系统介绍”）就跨系统选择分类。
                
                【输出要求】
                1. 只输出一个 JSON 数组，不要包含任何多余文字。
                2. 数组中的每个元素是一个对象，字段为：
                   - id：字符串，对应下面列表中的某个 id（必须严格一致）
                   - score：0 到 1 之间的小数
                   - reason：简要中文说明为什么匹配
                3. 如果你认为所有分类都不太匹配，可以返回空数组 []。
                
                示例输出：
                [
                  {"id": "biz-oa-intro", "score": 0.92, "reason": "问题询问OA系统整体功能"},
                  {"id": "biz-oa-security", "score": 0.88, "reason": "问题同时关心OA系统的数据安全"}
                ]
                
                【分类列表（仅叶子节点）】
                """);

        for (IntentNode node : leafNodes) {
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

        sb.append("\n【用户问题】\n").append(question).append("\n");

        return sb.toString();
    }
}
