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

import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 用户输入审核：判断本轮请求能否进入执行流程
 * <p>
 * 实现之间只共享类别与失败语义，判断能力不同：LLM 结合上一轮理解指代，阿里云只审本轮原文
 */
@FunctionalInterface
public interface InputRiskDetector {

    /**
     * @param question     本轮原文
     * @param previousTurn 上一轮，用不到的实现不调用，就不会查库
     * @return 命中的风险类别，空集合放行
     * @throws RuntimeException 拿不到有效裁决时抛出，调用方按拒绝处理
     */
    Set<InputRisk> detect(String question, Supplier<Optional<PreviousTurn>> previousTurn);
}
