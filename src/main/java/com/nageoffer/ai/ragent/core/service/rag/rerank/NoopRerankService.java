package com.nageoffer.ai.ragent.core.service.rag.rerank;

import com.nageoffer.ai.ragent.core.dto.rag.RAGHit;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 默认 Rerank：不做任何重排，直接截取前 topN。
 * <p>
 * 配置示例：
 * rag:
 * rerank:
 * enabled: false
 */
@Service
@ConditionalOnProperty(name = "ai.rerank.provider", havingValue = "noop")
public class NoopRerankService implements RerankService {

    @Override
    public List<RAGHit> rerank(String query, List<RAGHit> candidates, int topN) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        if (topN <= 0 || candidates.size() <= topN) {
            return candidates;
        }
        return candidates.stream()
                .limit(topN)
                .collect(Collectors.toList());
    }
}

