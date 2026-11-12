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

package com.nageoffer.ai.ragent.mcp.dao.result;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 可下单配置连同它所属款的展示字段，省掉执行器再查一次款
 */
@Data
public class SkuDetailResult {

    private String skuCode;

    private String spuCode;

    private String spuName;

    private String name;

    private String category;

    private String subCategory;

    /**
     * 规格键值 JSON 原文，展示顺序由 BitToolSupport.specs 定
     */
    private String specs;

    /**
     * 所属款的公共属性 JSON 原文，配置本身不重复存
     */
    private String spuSpecs;

    private BigDecimal price;

    private Integer stock;

    private String status;
}
