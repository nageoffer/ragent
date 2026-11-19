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

package com.nageoffer.ai.ragent.infra.jev;

/**
 * Jev 的单条 Noul 判断
 * <p>
 * Noul 返回「答案为是的概率」而非程度分，{@code instructions} 必须写成一句能回答是否的命题
 * {@code key} 由调用方自定，答案按同一个 key 归位，不传给模型；两个 criteria 可空
 */
public record JevNoulQuestion(
        String key,
        String instructions,
        String trueCriteria,
        String falseCriteria
) {
}
