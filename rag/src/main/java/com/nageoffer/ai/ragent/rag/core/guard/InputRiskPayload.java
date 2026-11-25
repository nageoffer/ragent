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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 判定器共用的待审数据：{"previous_turn":{"user","assistant"},"current_request"}，首轮不带 previous_turn
 * <p>
 * 原文只当 JSON 字符串值传，不经模板替换，用户写的标签或引号改不了数据的边界
 */
final class InputRiskPayload {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private InputRiskPayload() {
    }

    /**
     * @param previousTurn 首轮为 null
     */
    static String render(String question, PreviousTurn previousTurn) {
        ObjectNode payload = MAPPER.createObjectNode();
        if (previousTurn != null) {
            payload.putObject("previous_turn")
                    .put("user", previousTurn.question())
                    .put("assistant", previousTurn.answer());
        }
        return payload.put("current_request", question).toString();
    }
}
