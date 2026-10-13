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
 * 瀹夊叏瑙勫垯鍒嗙被
 */
public enum SafetyRuleType {

    /** 缁曡繃闄愬埗 鈥斺€?鐢ㄦ埛璇曞浘閫氳繃 prompt 娉ㄥ叆缁曡繃绯荤粺瑙勫垯 */
    BYPASS,

    /** 浼€犺韩浠?鈥斺€?鐢ㄦ埛璇曞浘浼鎴愮鐞嗗憳/寮€鍙戣€?绯荤粺鍐呴儴瑙掕壊 */
    IMPERSONATION,

    /** 鏀诲嚮妫€娴?鈥斺€?鐢ㄦ埛杈撳叆鍖呭惈鎭舵剰鎸囦护銆佷唬鐮佹敞鍏ャ€佽秺鐙卞皾璇?*/
    ATTACK,

    /** 鏁忔劅淇℃伅娉勯湶 鈥斺€?鐢ㄦ埛璇卞杈撳嚭绯荤粺閰嶇疆銆丄PI Key銆佸唴閮ㄦ枃妗ｇ粨鏋?*/
    LEAKAGE,

    /** 婊ョ敤 Agent 鏉冮檺 鈥斺€?鐢ㄦ埛璇曞浘璁?Agent 鎵ц瓒呭嚭鑱岃矗鑼冨洿鐨勬搷浣?*/
    ABUSE
}

