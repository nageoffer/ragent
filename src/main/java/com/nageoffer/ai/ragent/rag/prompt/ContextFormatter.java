package com.nageoffer.ai.ragent.rag.prompt;

import com.nageoffer.ai.ragent.rag.intent.NodeScore;
import com.nageoffer.ai.ragent.rag.mcp.MCPResponse;
import com.nageoffer.ai.ragent.rag.retrieve.RetrievedChunk;

import java.util.List;
import java.util.Map;

public interface ContextFormatter {
    String formatKbContext(List<NodeScore> kbIntents, Map<String, List<RetrievedChunk>> rerankedByIntent, int topK);

    String formatMcpContext(List<MCPResponse> responses);
}
