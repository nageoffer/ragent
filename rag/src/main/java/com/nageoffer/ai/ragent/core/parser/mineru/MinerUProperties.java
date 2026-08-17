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

package com.nageoffer.ai.ragent.core.parser.mineru;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * MinerU SaaS 配置
 * <p>
 * 配置项绑定到 application.yaml 的 {@code mineru.*} 节
 */
@Configuration
@ConfigurationProperties("mineru")
@Data
public class MinerUProperties {

    /**
     * MinerU SaaS API 根地址，默认 <a href="https://mineru.net/api/v4">...</a>
     */
    private String apiUrl = "https://mineru.net/api/v4";

    /**
     * API token，从环境变量 MINERU_API_KEY 注入
     */
    private String apiKey;

    /**
     * 内部轮询间隔(秒)默认 5
     */
    private int pollIntervalSeconds = 5;

    /**
     * 单任务超时(秒)默认 300
     */
    private int timeoutSeconds = 300;

    /**
     * 是否提取表格，默认 true
     */
    private boolean enableTable = true;

    /**
     * 是否提取公式，默认 true
     */
    private boolean enableFormula = true;

    /**
     * 是否强制 OCR，默认 false(原生文本 PDF 不需要)
     * <p>
     * 字段名不带 {@code is} 前缀:Lombok 对 boolean 字段会自动加 {@code is} 前缀生成
     * getter {@link #isOcr()}，setter 为 {@link #setOcr(boolean)};
     * Spring {@code @ConfigurationProperties} 据 setter 名识别属性为 {@code ocr}，
     * 故 yaml 必须写 {@code mineru.ocr: false} 而非 {@code is-ocr}
     */
    private boolean ocr = false;

    /**
     * 语言代码，遵循 MinerU(PaddleOCR)规范，默认 ch(中英文)
     */
    private String language = "ch";

    /**
     * 全局 outstanding 任务上限，防止打爆 SaaS，默认 16
     */
    private int concurrencyLimit = 16;

    /**
     * MinerU 解析分布式信号量名称
     */
    private String semaphoreName = "rag:mineru:parse";

    /**
     * 获取 MinerU 解析许可最大等待时间（秒）
     */
    private int maxWaitSeconds = 30;

    /**
     * MinerU 解析许可自动释放时间（秒），需大于 timeoutSeconds
     */
    private int leaseSeconds = 900;
}
