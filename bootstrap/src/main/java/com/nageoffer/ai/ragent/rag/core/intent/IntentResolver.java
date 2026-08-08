/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.ragent.rag.core.intent;

import cn.hutool.core.collection.CollUtil;
import com.nageoffer.ai.ragent.rag.dto.IntentCandidate;
import com.nageoffer.ai.ragent.rag.dto.IntentGroup;
import com.nageoffer.ai.ragent.rag.dto.SubQuestionIntent;
import com.nageoffer.ai.ragent.framework.trace.RagTraceNode;
import com.nageoffer.ai.ragent.rag.core.rewrite.RewriteResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.HashMap;
import java.util.concurrent.Executor;
import java.util.PriorityQueue;

import static com.nageoffer.ai.ragent.rag.constant.RAGConstant.INTENT_MIN_SCORE;
import static com.nageoffer.ai.ragent.rag.constant.RAGConstant.MAX_INTENT_COUNT;
import static com.nageoffer.ai.ragent.rag.enums.IntentKind.SYSTEM;

@Slf4j
@Service
@RequiredArgsConstructor
public class IntentResolver {

    @Qualifier("defaultIntentClassifier")
    private final IntentClassifier intentClassifier;
    private final Executor intentClassifyExecutor;

    @RagTraceNode(name = "intent-resolve", type = "INTENT")
    public List<SubQuestionIntent> resolve(RewriteResult rewriteResult) {
        List<String> subQuestions = CollUtil.isNotEmpty(rewriteResult.subQuestions())
                ? rewriteResult.subQuestions()
                : List.of(rewriteResult.rewrittenQuestion());
        List<CompletableFuture<SubQuestionIntent>> tasks = subQuestions.stream()
                .map(q -> CompletableFuture.supplyAsync(
                        () -> {
                            try {
                                return new SubQuestionIntent(q, classifyIntents(q));
                            } catch (Exception e) {
                                log.error("子问题意图分类失败，降级为空意图，question：{}", q, e);
                                return new SubQuestionIntent(q, List.of());
                            }
                        },
                        intentClassifyExecutor))
                .toList();
        List<SubQuestionIntent> subIntents = tasks.stream()
                .map(CompletableFuture::join)
                .toList();
        return capTotalIntents(subIntents);
    }

    public IntentGroup mergeIntentGroup(List<SubQuestionIntent> subIntents) {
        List<NodeScore> mcpIntents = new ArrayList<>();
        List<NodeScore> kbIntents = new ArrayList<>();
        for (SubQuestionIntent si : subIntents) {
            mcpIntents.addAll(NodeScoreFilters.mcp(si.nodeScores()));
            kbIntents.addAll(NodeScoreFilters.kb(si.nodeScores()));
        }
        return new IntentGroup(mcpIntents, kbIntents);
    }

    public boolean isSystemOnly(List<NodeScore> nodeScores) {
        return nodeScores.size() == 1
                && nodeScores.get(0).getNode() != null
                && nodeScores.get(0).getNode().getKind() == SYSTEM;
    }

    private List<NodeScore> classifyIntents(String question) {
        List<NodeScore> scores = intentClassifier.classifyTargets(question);
        return scores.stream()
                .filter(ns -> ns.getScore() >= INTENT_MIN_SCORE)
                .limit(MAX_INTENT_COUNT)
                .toList();
    }

    /**
     * 限制总意图数量不超过 MAX_INTENT_COUNT。
     *
     * 每个非空子问题保留一个最高分意图；其余配额分配给全局分数最高的候选。
     */
    private List<SubQuestionIntent> capTotalIntents(List<SubQuestionIntent> subIntents) {
        List<RankedIntentCandidate> guaranteedIntents = new ArrayList<>();
        PriorityQueue<RankedIntentCandidate> additionalIntents = new PriorityQueue<>(this::compareAdditionalPriority);

        int totalIntents = 0;
        int remaining = MAX_INTENT_COUNT;
        int order = 0;

        for (int i = 0; i < subIntents.size(); i++) {
            RankedIntentCandidate topIntent = null;

            for (NodeScore nodeScore : subIntents.get(i).nodeScores()) {
                totalIntents++;

                RankedIntentCandidate candidate = new RankedIntentCandidate(new IntentCandidate(i, nodeScore), order++);

                if (topIntent == null || compareScore(candidate, topIntent) > 0) {
                    if (topIntent != null) {
                        retainAdditionalIntent(additionalIntents, topIntent, remaining);
                    }
                    topIntent = candidate;
                } else {
                    retainAdditionalIntent(additionalIntents, candidate, remaining);
                }
            }

            if (topIntent != null) {
                guaranteedIntents.add(topIntent);
                remaining = Math.max(0, MAX_INTENT_COUNT - guaranteedIntents.size());
                trimAdditionalIntents(additionalIntents, remaining);
            }
        }

        if (totalIntents <= MAX_INTENT_COUNT) {
            return subIntents;
        }

        List<IntentCandidate> guaranteed = guaranteedIntents.stream()
                .map(RankedIntentCandidate::candidate)
                .toList();

        List<IntentCandidate> additional = additionalIntents.stream()
                .sorted((left, right) -> compareAdditionalPriority(right, left))
                .map(RankedIntentCandidate::candidate)
                .toList();

        return rebuildSubIntents(subIntents, guaranteed, additional);
    }

    private void retainAdditionalIntent(PriorityQueue<RankedIntentCandidate> candidates,
            RankedIntentCandidate candidate,
            int limit) {
        if (limit <= 0) {
            return;
        }

        if (candidates.size() < limit) {
            candidates.offer(candidate);
            return;
        }

        if (compareAdditionalPriority(candidate, candidates.peek()) > 0) {
            candidates.poll();
            candidates.offer(candidate);
        }
    }

    private void trimAdditionalIntents(PriorityQueue<RankedIntentCandidate> candidates, int limit) {
        while (candidates.size() > limit) {
            candidates.poll();
        }
    }

    /**
     * 分数高者优先；同分时保持原始遍历顺序优先。
     */
    private int compareScore(RankedIntentCandidate left, RankedIntentCandidate right) {
        int scoreComparison = Double.compare(
                left.candidate().nodeScore().getScore(),
                right.candidate().nodeScore().getScore());
        return scoreComparison != 0
                ? scoreComparison
                : Integer.compare(right.order(), left.order());
    }

    /**
     * 小顶堆比较器：堆顶始终是当前额外候选中最应被淘汰的条目。
     */
    private int compareAdditionalPriority(RankedIntentCandidate left, RankedIntentCandidate right) {
        return compareScore(left, right);
    }

    private record RankedIntentCandidate(IntentCandidate candidate, int order) {
    }

    /**
     * 根据选中的意图重建 SubQuestionIntent 列表
     */
    private List<SubQuestionIntent> rebuildSubIntents(List<SubQuestionIntent> originalSubIntents,
            List<IntentCandidate> guaranteedIntents,
            List<IntentCandidate> additionalIntents) {
        // 合并所有选中的意图
        List<IntentCandidate> allSelected = new ArrayList<>(guaranteedIntents);
        allSelected.addAll(additionalIntents);

        // 按子问题索引分组
        Map<Integer, List<NodeScore>> groupedByIndex = new HashMap<>();
        for (IntentCandidate candidate : allSelected) {
            groupedByIndex.computeIfAbsent(candidate.subQuestionIndex(), k -> new ArrayList<>())
                    .add(candidate.nodeScore());
        }

        // 重建结果
        List<SubQuestionIntent> result = new ArrayList<>();
        for (int i = 0; i < originalSubIntents.size(); i++) {
            SubQuestionIntent original = originalSubIntents.get(i);
            List<NodeScore> retained = groupedByIndex.getOrDefault(i, List.of());
            result.add(new SubQuestionIntent(original.subQuestion(), retained));
        }
        return result;
    }
}
