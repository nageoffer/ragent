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
 * 瀹夊叏瑙勫垯鎵ц浼樺厛绾у父閲?
 * <p>
 * 鍊艰秺灏忚秺鍏堟墽琛岋紝鍓嶅簭瑙勫垯寮€閿€灏忥紝灏芥棭鎷︽埅鍙伩鍏嶅悗缁鏉傛娴?
 */
public final class SafetyRuleOrder {

    /** 杈撳叆闀垮害 鈥斺€?鏈€绠€鍗曪紝鍏堟埅鏂?*/
    public static final int INPUT_LENGTH = 100;

    /** 鍏抽敭璇嶅尮閰?鈥斺€?瀛楃涓?contains锛屽紑閿€浣?*/
    public static final int KEYWORD = 200;

    /** 鏁忔劅妯″紡姝ｅ垯 鈥斺€?棰勭紪璇戞鍒?*/
    public static final int SENSITIVE_PATTERN = 300;

    /** Prompt 娉ㄥ叆妫€娴?鈥斺€?妯″紡鍖归厤 */
    public static final int PROMPT_INJECTION = 400;

    private SafetyRuleOrder() {
    }
}

