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
 * 按款聚合的浏览结果
 * <p>
 * 价格区间、总库存与配置数都是按可下单配置聚出来的，不属于商品款表的任何一列，
 * 所以单起一个结果对象而不是塞回 ProductDO——塞回去那个类就成了「有时有值有时没值」的半成品
 */
@Data
public class ProductSummaryResult {

    private String spuCode;

    private String name;

    private String category;

    private String subCategory;

    private String brand;

    private String brandOwner;

    /**
     * 款级公共属性 JSON 原文
     */
    private String specs;

    private String tags;

    private String sellingPoint;

    /**
     * 落在筛选条件内的最低价
     */
    private BigDecimal minPrice;

    /**
     * 落在筛选条件内的最高价
     */
    private BigDecimal maxPrice;

    private Integer stock;

    private Integer skuCount;
}
