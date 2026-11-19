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

package com.nageoffer.ai.ragent.rag.config.validation;

import lombok.Getter;

import java.util.List;

/**
 * Jev 意图识别配置不完整异常
 */
@Getter
public class JevConfigException extends RuntimeException {

    private final transient List<JevConfigValidator.Violation> violations;

    public JevConfigException(List<JevConfigValidator.Violation> violations) {
        super("Jev 意图识别配置不完整（" + violations.size() + " 项）");
        this.violations = violations;
    }
}
