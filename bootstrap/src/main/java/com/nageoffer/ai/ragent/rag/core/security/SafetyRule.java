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

/**
 * 瀹夊叏瑙勫垯鎺ュ彛
 * <p>
 * 瀹炵幇绫诲姞 {@code @Component} + {@code @Order} 鍗冲彲鑷姩鎺ュ叆瑙勫垯閾撅紝
 * 澶嶇敤 Spring Bean 鑷姩鍙戠幇妯″紡锛堝弬鑰?{@code DefaultMcpToolRegistry}锛夈€?
 */
public interface SafetyRule {

    /** 瑙勫垯鍞竴鏍囪瘑 */
    String getId();

    /** 瑙勫垯涓枃鍚嶇О */
    String getName();

    /** 瑙勫垯鍒嗙被 */
    SafetyRuleType getType();

    /** 瑙勫垯鏄惁鍚敤锛堥€氬父浠?SecurityProperties 璇诲彇锛?*/
    boolean isEnabled();

    /**
     * 鎵ц瀹夊叏妫€鏌?
     *
     * @param question 鐢ㄦ埛闂锛堝師濮嬮棶棰橈級
     * @param context  瀹夊叏妫€鏌ヤ笂涓嬫枃
     * @return 妫€娴嬬粨鏋滐紝PASS / BLOCK / WARN
     */
    SafetyResult evaluate(String question, SafetyCheckContext context);
}

