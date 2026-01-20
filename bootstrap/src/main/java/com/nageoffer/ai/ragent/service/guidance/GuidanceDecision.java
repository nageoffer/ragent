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

package com.nageoffer.ai.ragent.service.guidance;

import com.nageoffer.ai.ragent.rag.intent.IntentNode;
import lombok.Getter;

import java.util.List;

/**
 * 引导式问答决策结果类
 * 用于表示对用户意图进行引导式问答后的决策结果，包括无需操作、需要进一步提示或已解决
 */
@Getter
public class GuidanceDecision {

    public enum Action {
        NONE,
        PROMPT,
        RESOLVED
    }

    private final Action action;
    private final String prompt;
    private final String resolvedQuestion;
    private final List<IntentNode> resolvedNodes;

    private GuidanceDecision(Action action, String prompt, String resolvedQuestion, List<IntentNode> resolvedNodes) {
        this.action = action;
        this.prompt = prompt;
        this.resolvedQuestion = resolvedQuestion;
        this.resolvedNodes = resolvedNodes;
    }

    public static GuidanceDecision none() {
        return new GuidanceDecision(Action.NONE, null, null, null);
    }

    public static GuidanceDecision prompt(String prompt) {
        return new GuidanceDecision(Action.PROMPT, prompt, null, null);
    }

    public static GuidanceDecision resolved(String question, IntentNode node) {
        if (node == null) {
            return none();
        }
        return new GuidanceDecision(Action.RESOLVED, null, question, List.of(node));
    }

    public static GuidanceDecision resolved(String question, List<IntentNode> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            return none();
        }
        return new GuidanceDecision(Action.RESOLVED, null, question, nodes);
    }

    public boolean isPrompt() {
        return action == Action.PROMPT;
    }

    public boolean isResolved() {
        return action == Action.RESOLVED;
    }
}
