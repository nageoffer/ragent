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

package com.nageoffer.ai.ragent.rag.core.security;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 瀹夊叏瀹¤鏃ュ織缁勪欢
 * <p>
 * 浣跨敤 SLF4J marker SAFETY_AUDIT 鏍囪鎵€鏈夊畨鍏ㄤ簨浠舵棩蹇楋紝
 * 鏂逛究鍚庣画鎺ュ叆 ELK/Splunk 绛夋棩蹇楀钩鍙拌繘琛屽畨鍏ㄦ€佸娍鍒嗘瀽銆?
 */
@Slf4j
@Component
public class SafetyAuditLogger {

    private static final String MARKER = "SAFETY_AUDIT";

    /**
     * 璁板綍瀹夊叏瀹¤浜嬩欢
     */
    public void log(SafetyAuditEvent event) {
        if (event == null) {
            return;
        }
        log.warn("[{}] 瀹夊叏浜嬩欢 | ruleId={} | ruleName={} | type={} | result={} | confidence={} | userId={} | conversationId={} | question={} | message={}",
                MARKER,
                event.getRuleId(),
                event.getRuleName(),
                event.getRuleType(),
                event.getResult(),
                String.format("%.2f", event.getConfidence()),
                StrUtil.emptyIfNull(event.getUserId()),
                StrUtil.emptyIfNull(event.getConversationId()),
                StrUtil.sub(StrUtil.emptyIfNull(event.getQuestion()), 0, 200),
                StrUtil.emptyIfNull(event.getMessage())
        );
    }
}

