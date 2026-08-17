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

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 记忆配置校验注解
 * 用于校验摘要配置的合理性
 */
@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = MemoryConfigValidator.class)
@Documented
public @interface ValidMemoryConfig {

    /**
     * 验证失败时的默认错误消息
     * <p>
     * JSR-303 规范强制要求此方法，即使在 Validator 中自定义了错误消息也必须保留
     * </p>
     */
    String message() default "记忆配置不合法";

    /**
     * 验证分组
     * <p>
     * JSR-303 规范强制要求此方法，用于支持分组验证场景（如：创建时验证、更新时验证）
     * 即使当前不使用分组验证，也必须保留此方法
     * </p>
     */
    Class<?>[] groups() default {};

    /**
     * 负载信息
     * <p>
     * JSR-303 规范强制要求此方法，用于携带验证相关的元数据（如：严重级别、错误码等）
     * 即使当前不使用 Payload，也必须保留此方法
     * </p>
     */
    Class<? extends Payload>[] payload() default {};
}
