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

package com.nageoffer.ai.ragent.agent.memory;

import java.util.List;

/**
 * 一次 Judge 调用的结论：要怎样改记忆，以及为什么这样改或不改；空决策即合法的不改
 * 只由解析处在校验通过后构造；下游只读这份原始结论，Edit 另生成执行计划，所以不额外做不可修改拷贝
 * 说明不代表已经保存，保存与否以提交结果为准
 */
public record AgentMemoryJudgement(List<AgentMemoryDecision> decisions, String explanation) {
}
