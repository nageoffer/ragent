package com.nageoffer.ai.ragent.rag.prompt;

import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.rag.intent.NodeScore;
import com.nageoffer.ai.ragent.rag.retrieve.RetrievedChunk;
import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@Builder
public class PromptContext {
    private String question;
    private String mcpContext;
    private String kbContext;
    private List<NodeScore> mcpIntents;
    private List<NodeScore> kbIntents;
    private Map<String, List<RetrievedChunk>> intentChunks;

    public boolean hasMcp() {
        return StrUtil.isNotBlank(mcpContext);
    }

    public boolean hasKb() {
        return StrUtil.isNotBlank(kbContext);
    }
}
