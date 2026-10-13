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
 * 瀹夊叏杩囨护鍣ㄦ帴鍙?
 * <p>
 * 鍦?RAG Pipeline "涓婁笅鏂囩粍瑁呬箣鍚庛€丩LM 璺敱涔嬪墠" 娉ㄥ叆锛?
 * 瀵瑰畬鏁磋姹傝繘琛岀‖鎬у畨鍏ㄦ鏌ャ€?
 */
@FunctionalInterface
public interface SafetyFilter {

    /**
     * 鎵ц瀹夊叏妫€鏌?
     *
     * @param context 瀹夊叏妫€鏌ヤ笂涓嬫枃锛堝惈 question + 缁勮濂界殑 messages锛?
     * @return PASS 鏀捐 / BLOCK 鎷掔粷 / WARN 鍛婅鍚庢斁琛?
     */
    SafetyResult check(SafetyCheckContext context);
}

