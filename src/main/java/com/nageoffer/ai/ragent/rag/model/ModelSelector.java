package com.nageoffer.ai.ragent.rag.model;

import com.nageoffer.ai.ragent.config.AIModelProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Slf4j
@Component
@RequiredArgsConstructor
public class ModelSelector {

    private final AIModelProperties properties;
    private final ModelHealthStore healthStore;

    public List<ModelTarget> selectChatCandidates() {
        return selectCandidates(properties.getChat());
    }

    public List<ModelTarget> selectEmbeddingCandidates() {
        return selectCandidates(properties.getEmbedding());
    }

    public List<ModelTarget> selectRerankCandidates() {
        return selectCandidates(properties.getRerank());
    }

    public ModelTarget selectDefaultEmbedding() {
        List<ModelTarget> targets = selectEmbeddingCandidates();
        return targets.isEmpty() ? null : targets.get(0);
    }

    private List<ModelTarget> selectCandidates(AIModelProperties.ModelGroup group) {
        if (group == null || group.getCandidates() == null) {
            return List.of();
        }
        Map<String, AIModelProperties.ProviderConfig> providers = properties.getProviders();
        List<AIModelProperties.ModelCandidate> raw = new ArrayList<>(group.getCandidates());

        raw.removeIf(c -> c == null || Boolean.FALSE.equals(c.getEnabled()));
        raw.sort(Comparator
                .comparing(AIModelProperties.ModelCandidate::getPriority, Comparator.nullsLast(Integer::compareTo))
                .thenComparing(AIModelProperties.ModelCandidate::getId, Comparator.nullsLast(String::compareTo)));

        if (group.getDefaultModel() != null) {
            AIModelProperties.ModelCandidate defaultCandidate = null;
            for (AIModelProperties.ModelCandidate candidate : raw) {
                if (group.getDefaultModel().equals(candidate.getId())) {
                    defaultCandidate = candidate;
                    break;
                }
            }
            if (defaultCandidate != null) {
                raw.remove(defaultCandidate);
                raw.add(0, defaultCandidate);
            }
        }

        List<ModelTarget> targets = new ArrayList<>();
        for (AIModelProperties.ModelCandidate candidate : raw) {
            String id = resolveId(candidate);
            if (healthStore.isOpen(id)) {
                continue;
            }
            AIModelProperties.ProviderConfig provider = providers.get(candidate.getProvider());
            if (provider == null && !"noop".equalsIgnoreCase(candidate.getProvider())) {
                log.warn("Model provider config missing: provider={}, modelId={}", candidate.getProvider(), id);
                continue;
            }
            targets.add(new ModelTarget(id, candidate, provider));
        }

        return targets;
    }

    private String resolveId(AIModelProperties.ModelCandidate candidate) {
        if (candidate == null) {
            return null;
        }
        if (candidate.getId() != null && !candidate.getId().isBlank()) {
            return candidate.getId();
        }
        return Objects.toString(candidate.getProvider(), "unknown") +
                "::" +
                Objects.toString(candidate.getModel(), "unknown");
    }

}
