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

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 瀹夊叏鍔熻兘閰嶇疆灞炴€?
 * <p>
 * 鍓嶇紑锛歿@code rag.security}
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "rag.security")
public class SecurityProperties {

    /** 瀹夊叏妯″潡鎬诲紑鍏筹紝false 鏃舵墍鏈夎鍒欎笉鐢熸晥 */
    private boolean enabled = true;

    /** 杈撳叆瀹夊叏閰嶇疆 */
    private InputConfig input = new InputConfig();

    /** 杈撳嚭瀹夊叏閰嶇疆 */
    private OutputConfig output = new OutputConfig();

    /** MCP 鏉冮檺閰嶇疆 */
    private McpConfig mcp = new McpConfig();

    /** 闃绘柇鏃剁殑鎷掔瓟鏂囨 */
    private String blockMessage = "鎶辨瓑锛屾娴嬪埌鎮ㄧ殑杈撳叆鍖呭惈涓嶅畨鍏ㄧ殑璇锋眰锛屽凡琚畨鍏ㄧ瓥鐣ユ嫤鎴€傚鏈夌枒闂鑱旂郴绠＄悊鍛樸€?;

    /** 鍛婅鏃剁殑鎻愮ず鏂囨妯℃澘锛寋rule} 浼氳鏇挎崲涓鸿鍒欏悕绉?*/
    private String warnMessage = "[瀹夊叏鎻愮ず] 鎮ㄧ殑璇锋眰瑙﹀彂浜嗗畨鍏ㄧ瓥鐣?({rule})锛屾湰娆″洖绛斿凡杩藉姞瀹夊叏绾︽潫銆?;

    @Data
    public static class InputConfig {
        private boolean enabled = true;
        private int maxLength = 4096;
    }

    @Data
    public static class OutputConfig {
        private boolean enabled = true;
        private boolean maskPhone = true;
        private boolean maskIdCard = true;
        private boolean maskEmail = true;
        private boolean maskApiKey = true;
        private boolean detectPromptLeakage = true;
    }

    @Data
    public static class McpConfig {
        private boolean enforceRoles = true;
    }
}

