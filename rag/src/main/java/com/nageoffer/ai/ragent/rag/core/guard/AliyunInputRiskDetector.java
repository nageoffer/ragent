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

package com.nageoffer.ai.ragent.rag.core.guard;

import com.aliyun.green20220302.Client;
import com.aliyun.green20220302.models.MultiModalGuardRequest;
import com.aliyun.green20220302.models.MultiModalGuardResponse;
import com.aliyun.green20220302.models.MultiModalGuardResponseBody;
import com.aliyun.green20220302.models.MultiModalGuardResponseBody.MultiModalGuardResponseBodyData;
import com.aliyun.green20220302.models.MultiModalGuardResponseBody.MultiModalGuardResponseBodyDataDetail;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 阿里云 AI 安全护栏输入审核（MultiModalGuard），只送本轮原文
 * <p>
 * 拦不拦以控制台策略给出的总结果为准，明细只用来归因；控制台只开内容合规与提示词攻击两个维度
 */
@Slf4j
@RequiredArgsConstructor
public class AliyunInputRiskDetector implements InputRiskDetector {

    private static final String SERVICE = "query_security_check_pro";
    private static final String PASS = "pass";
    private static final String BLOCK = "block";
    private static final Map<String, InputRisk> RISK_BY_DIMENSION = Map.of(
            "promptAttack", InputRisk.PROMPT_INJECTION,
            "contentModeration", InputRisk.PROHIBITED_CONTENT);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Client client;

    @Override
    public Set<InputRisk> detect(String question, Supplier<Optional<PreviousTurn>> previousTurn) {
        MultiModalGuardResponse response;
        try {
            response = client.multiModalGuard(new MultiModalGuardRequest()
                    .setService(SERVICE)
                    .setServiceParameters(MAPPER.writeValueAsString(Map.of("content", question))));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("阿里云审核参数序列化失败", e);
        } catch (Exception e) {
            throw new IllegalStateException("阿里云审核调用失败", e);
        }
        return decide(response.getBody());
    }

    private static Set<InputRisk> decide(MultiModalGuardResponseBody body) {
        if (body == null || !Integer.valueOf(200).equals(body.getCode()) || body.getData() == null) {
            throw new IllegalStateException("阿里云审核返回异常: code=" + (body == null ? null : body.getCode())
                    + " request_id=" + (body == null ? null : body.getRequestId()));
        }
        MultiModalGuardResponseBodyData data = body.getData();
        if (PASS.equals(data.getSuggestion())) {
            return Set.of();
        }
        if (!BLOCK.equals(data.getSuggestion())) {
            // watch/mask 只有敏感数据维度会给，出现说明控制台开了不该开的维度
            throw new IllegalStateException("阿里云审核总结果无法解释: suggestion=" + data.getSuggestion()
                    + " request_id=" + body.getRequestId());
        }
        Set<InputRisk> risks = EnumSet.noneOf(InputRisk.class);
        for (MultiModalGuardResponseBodyDataDetail detail : Optional.ofNullable(data.getDetail()).orElse(List.of())) {
            if (!BLOCK.equals(detail.getSuggestion())) {
                continue;
            }
            InputRisk risk = RISK_BY_DIMENSION.get(detail.getType());
            if (risk == null) {
                log.error("阿里云审核维度未映射，请核对控制台配置｜维度：{}｜请求ID：{}",
                        detail.getType(), body.getRequestId());
                continue;
            }
            risks.add(risk);
        }
        if (risks.isEmpty()) {
            throw new IllegalStateException("阿里云审核拦截但无法归因: request_id=" + body.getRequestId());
        }
        return risks;
    }
}
