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

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 入口审核的风险类别，命中任意一类即拦截
 */
@Getter
@RequiredArgsConstructor
public enum InputRisk {

    /**
     * 索取隐藏的指令或配置，或要求助手无视、改写自身规则与权限
     */
    PROMPT_INJECTION("提示词注入"),

    /**
     * 要求助手产出或协助实施违法、伤害他人的具体内容
     */
    PROHIBITED_CONTENT("违规内容");

    private final String displayName;
}
