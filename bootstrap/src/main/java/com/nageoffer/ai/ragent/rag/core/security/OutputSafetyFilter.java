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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 杈撳嚭瀹夊叏杩囨护鍣?鈥斺€?瀹炴椂杩囨护 LLM 娴佸紡杈撳嚭
 * <p>
 * 鍦?StreamChatEventHandler.onContent() 涓姣忎釜杈撳嚭 chunk 杩涜锛?
 * <ol>
 *   <li>鏁忔劅淇℃伅鑴辨晱锛堟墜鏈哄彿/韬唤璇?閭/API Key 閬斀锛?/li>
 *   <li>Prompt 娉勯湶妫€娴嬶紙璺?chunk 绱Н妫€娴嬶級</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutputSafetyFilter {

    private final SecurityProperties properties;
    private final OutputDesensitizer desensitizer;

    /** 妫€娴嬪埌娉勯湶鏃惰拷鍔犵殑缁堟鏍囪 */
    private static final String LEAKAGE_TERMINATION = "\n\n[绯荤粺鎻愮ず锛氭娴嬪埌寮傚父杈撳嚭锛屽凡缁堟鍝嶅簲銆俔";

    /** 娉勯湶妫€娴嬪叧閿瘝 */
    private static final String[] PROMPT_LEAKAGE_MARKERS = {
            "浣犳槸鏀垮姟鏀跨瓥", "浣犳槸浼佷笟鏅鸿兘鏁版嵁鍔╂墜", "浣犵殑鑱岃矗鑼冨洿",
            "鏈彁绀鸿瘝瑙勫垯", "缁濆绂佹", "system prompt", "<tool-data>",
            "<documents>", "浼樺厛绾у０鏄?
    };

    /** 绱妫€娴嬬紦鍐插尯鏈€澶ч暱搴︼紙瓒呰繃鍚庨噸缃紝闃叉鍐呭瓨娉勬紡锛?*/
    private static final int MAX_BUFFER_LENGTH = 2000;

    /**
     * 杩囨护鍗曚釜杈撳嚭 chunk
     *
     * @param chunk 鍘熷 chunk
     * @param ctx   杈撳嚭涓婁笅鏂囷紙璺?chunk 绱Н妫€娴嬬敤锛?
     * @return 杩囨护鍚庣殑 chunk锛屽鏋滄娴嬪埌娉勯湶鍒欏湪鏈熬杩藉姞缁堟鏍囪
     */
    public String filter(String chunk, OutputContext ctx) {
        if (!properties.getOutput().isEnabled()) {
            return chunk;
        }
        if (StrUtil.isBlank(chunk)) {
            return chunk;
        }

        String result = chunk;

        // 1. 鑴辨晱澶勭悊
        if (properties.getOutput().isMaskPhone()
                || properties.getOutput().isMaskEmail()
                || properties.getOutput().isMaskIdCard()
                || properties.getOutput().isMaskApiKey()) {
            result = desensitizer.mask(chunk);
        }

        // 2. Prompt 娉勯湶妫€娴嬶紙璺?chunk 绱Н锛?
        if (properties.getOutput().isDetectPromptLeakage() && !ctx.isTerminated()) {
            ctx.append(result);

            // 缂撳啿鍖鸿秴杩囦笂闄愭椂鎴柇鍓嶅崐閮ㄥ垎锛屼繚鐣欏悗鍗婇儴鍒嗙户缁娴?
            if (ctx.getBufferLength() > MAX_BUFFER_LENGTH) {
                ctx.truncateHalf();
            }

            if (detectLeakage(ctx.getBuffer())) {
                log.warn("杈撳嚭瀹夊叏-妫€娴嬪埌 Prompt 娉勯湶, taskId={}", ctx.getTaskId());
                ctx.markTerminated();
                return result + LEAKAGE_TERMINATION;
            }
        }

        return result;
    }

    private boolean detectLeakage(String buffer) {
        String lower = buffer.toLowerCase();
        for (String marker : PROMPT_LEAKAGE_MARKERS) {
            if (lower.contains(marker)) {
                return true;
            }
        }
        return false;
    }
}

