package com.nageoffer.ai.ragent.rag.prompt;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.rag.intent.NodeScore;
import com.nageoffer.ai.ragent.rag.mcp.MCPResponse;
import com.nageoffer.ai.ragent.rag.mcp.MCPService;
import com.nageoffer.ai.ragent.rag.retrieve.RetrievedChunk;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

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
                    String body = chunks.stream()
                            .limit(topK)
                            .map(RetrievedChunk::getText)
                            .collect(Collectors.joining("\n"));
                    return "#### 意图：" + ns.getNode().getName() + "\n````text\n" + body + "\n````";
                })
                .filter(StrUtil::isNotBlank)
                .collect(Collectors.joining("\n\n"));
    }

    @Override
    public String formatMcpContext(List<MCPResponse> responses) {
        if (CollUtil.isEmpty(responses) || responses.stream().noneMatch(MCPResponse::isSuccess)) {
            return "";
        }
        return mcpService.mergeResponsesToText(responses);
    }
}
