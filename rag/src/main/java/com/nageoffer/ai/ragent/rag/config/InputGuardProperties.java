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

package com.nageoffer.ai.ragent.rag.config;

import lombok.Data;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 用户输入审核配置
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "ragent.input-guard")
public class InputGuardProperties {

    /**
     * 审核实现，写错值启动即失败；none 只能显式配置，审核故障不会自动切过去
     */
    private Type type = Type.LLM;

    private Aliyun aliyun = new Aliyun();

    @Getter
    @RequiredArgsConstructor
    public enum Type {
        LLM("大模型"), ALIYUN("阿里云安全护栏"), JEV("Jev"), NONE("未启用");

        private final String displayName;
    }

    @Data
    public static class Aliyun {

        private String endpoint = "green-cip.cn-shanghai.aliyuncs.com";

        private String regionId = "cn-shanghai";

        private String accessKeyId;

        private String accessKeySecret;
    }
}
