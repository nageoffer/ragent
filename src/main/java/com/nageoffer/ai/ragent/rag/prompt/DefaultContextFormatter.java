package com.nageoffer.ai.ragent.rag.prompt;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.rag.intent.IntentNode;
import com.nageoffer.ai.ragent.rag.intent.NodeScore;
import com.nageoffer.ai.ragent.rag.mcp.MCPResponse;
import com.nageoffer.ai.ragent.rag.mcp.MCPService;
import com.nageoffer.ai.ragent.rag.retrieve.RetrievedChunk;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DefaultContextFormatter implements ContextFormatter {

    private final MCPService mcpService;

    @Override
    public String formatKbContext(List<NodeScore> kbIntents, Map<String, List<RetrievedChunk>> rerankedByIntent, int topK) {
        if (CollUtil.isEmpty(kbIntents) || rerankedByIntent == null || rerankedByIntent.isEmpty()) {
            return "";
        }

        return kbIntents.stream()
                .map(ns -> {
                    List<RetrievedChunk> chunks = rerankedByIntent.get(ns.getNode().getId());
                    if (CollUtil.isEmpty(chunks)) {
                        return "";
                    }
                    String title = StrUtil.isNotBlank(ns.getNode().getFullPath())
                            ? ns.getNode().getFullPath()
                            : ns.getNode().getName();
                    String snippet = StrUtil.emptyIfNull(ns.getNode().getPromptSnippet()).trim();
                    String body = chunks.stream()
                            .limit(topK)
                            .map(RetrievedChunk::getText)
                            .collect(Collectors.joining("\n"));
                    StringBuilder block = new StringBuilder();
                    block.append("#### 意图：").append(title).append("\n");
                    if (StrUtil.isNotBlank(snippet)) {
                        block.append("#### 意图规则\n").append(snippet).append("\n");
                    }
                    block.append("#### 知识库片段\n````text\n").append(body).append("\n````");
                    return block.toString();
                })
                .filter(StrUtil::isNotBlank)
                .collect(Collectors.joining("\n\n"));
    }

    @Override
    public String formatMcpContext(List<MCPResponse> responses, List<NodeScore> mcpIntents) {
        if (CollUtil.isEmpty(responses) || responses.stream().noneMatch(MCPResponse::isSuccess)) {
            return "";
        }
        if (CollUtil.isEmpty(mcpIntents)) {
            return mcpService.mergeResponsesToText(responses);
        }

        Map<String, IntentNode> toolToIntent = new LinkedHashMap<>();
        for (NodeScore ns : mcpIntents) {
            IntentNode node = ns.getNode();
            if (node == null || StrUtil.isBlank(node.getMcpToolId())) {
                continue;
            }
            toolToIntent.putIfAbsent(node.getMcpToolId(), node);
        }

        Map<String, List<MCPResponse>> grouped = responses.stream()
                .filter(MCPResponse::isSuccess)
                .filter(r -> StrUtil.isNotBlank(r.getToolId()))
                .collect(Collectors.groupingBy(MCPResponse::getToolId));

        return toolToIntent.entrySet().stream()
                .map(entry -> {
                    List<MCPResponse> toolResponses = grouped.get(entry.getKey());
                    if (CollUtil.isEmpty(toolResponses)) {
                        return "";
                    }
                    IntentNode node = entry.getValue();
                    String title = StrUtil.isNotBlank(node.getFullPath())
                            ? node.getFullPath()
                            : node.getName();
                    String snippet = StrUtil.emptyIfNull(node.getPromptSnippet()).trim();
                    String body = mcpService.mergeResponsesToText(toolResponses);
                    if (StrUtil.isBlank(body)) {
                        return "";
                    }
                    StringBuilder block = new StringBuilder();
                    block.append("#### 意图：").append(title).append("\n");
                    if (StrUtil.isNotBlank(snippet)) {
                        block.append("#### 意图规则\n").append(snippet).append("\n");
                    }
                    block.append("#### 动态数据片段\n").append(body);
                    return block.toString();
                })
                .filter(StrUtil::isNotBlank)
                .collect(Collectors.joining("\n\n"));
    }
}
