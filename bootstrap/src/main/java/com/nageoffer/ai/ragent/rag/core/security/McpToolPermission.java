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

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * MCP 宸ュ叿鏉冮檺娉ㄨВ
 * <p>
 * 鏍囪鍦?{@code McpToolExecutor} 瀹炵幇绫讳笂锛屽０鏄庤皟鐢ㄨ宸ュ叿鎵€闇€鐨勮鑹层€?
 * 鏈爣璁扮殑宸ュ叿瑙嗕负鏃犻渶鐗规畩鏉冮檺锛堟墍鏈夌櫥褰曠敤鎴峰彲璋冪敤锛夈€?
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface McpToolPermission {

    /** 闇€瑕佺殑瑙掕壊鍒楄〃 */
    String[] requiredRoles() default {};

    /** 鏄惁浠呯鐞嗗憳鍙皟鐢?*/
    boolean adminOnly() default false;
}

