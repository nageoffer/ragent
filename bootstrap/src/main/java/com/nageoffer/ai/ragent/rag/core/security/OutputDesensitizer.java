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

import java.util.regex.Pattern;

/**
 * 鏁忔劅淇℃伅鑴辨晱宸ュ叿绫?
 * <p>
 * 瀵?LLM 杈撳嚭涓殑闅愮鏁版嵁杩涜瀹炴椂閬斀锛?
 * <ul>
 *   <li>鎵嬫満鍙凤細138****5678</li>
 *   <li>韬唤璇侊細320***********1234</li>
 *   <li>閭锛歶***@domain.com</li>
 *   <li>API Key锛歴k-****...****</li>
 * </ul>
 */
@Slf4j
@Component
public class OutputDesensitizer {

    /** 鎵嬫満鍙凤細淇濈暀鍓?鍚? */
    private static final Pattern PHONE_MASK = Pattern.compile("(1[3-9]\\d)(\\d{4})(\\d{4})");

    /** 韬唤璇侊細淇濈暀鍓?鍚? */
    private static final Pattern ID_CARD_MASK = Pattern.compile("(\\d{3})\\d{11}(\\d{4})");

    /** 閭锛氫繚鐣欓瀛楃鍜屽煙鍚?*/
    private static final Pattern EMAIL_MASK = Pattern.compile("(\\w)[^@]*(@\\w+\\.\\w+)");

    /** API Key锛氫繚鐣欓灏惧悇4浣?*/
    private static final Pattern API_KEY_MASK = Pattern.compile("(sk-[a-zA-Z0-9]{4})[a-zA-Z0-9_-]+([a-zA-Z0-9]{4})");

    /**
     * 瀵规枃鏈腑鐨勬晱鎰熶俊鎭繘琛岃劚鏁?
     *
     * @param text 鍘熷鏂囨湰
     * @return 鑴辨晱鍚庣殑鏂囨湰
     */
    public String mask(String text) {
        if (StrUtil.isBlank(text)) {
            return text;
        }

        String result = text;
        result = PHONE_MASK.matcher(result).replaceAll("$1****$3");
        result = ID_CARD_MASK.matcher(result).replaceAll("$1***********$2");
        result = EMAIL_MASK.matcher(result).replaceAll("$1***$2");
        result = API_KEY_MASK.matcher(result).replaceAll("$1****$2");

        return result;
    }
}

