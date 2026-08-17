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

package com.nageoffer.ai.ragent.user.enums;

import lombok.Getter;

/**
 * 用户角色枚举
 * 定义系统中的用户角色类型
 */
@Getter
public enum UserRole {

    /**
     * 管理员角色
     */
    ADMIN("admin"),

    /**
     * 普通用户角色
     */
    USER("user");

    /**
     * 角色编码
     */
    private final String code;

    /**
     * 构造函数
     *
     * @param code 角色编码
     */
    UserRole(String code) {
        this.code = code;
    }
}
