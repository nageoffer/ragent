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
import java.sql.Timestamp;

/**
 * 购物车一行，连同商品的现价、库存与品类
 * <p>
 * 加购价与现价两列都要给：降价了、涨价了、缺货了都得在结算前先说清楚
 */
@Data
public class CartLineResult {

    private Long id;

    private String userId;

    private String skuCode;

    private String skuName;

    private String category;

    private Integer quantity;

    /**
     * 加购价快照，与现价现比得出降价商品
     */
    private BigDecimal addedPrice;

    private BigDecimal price;

    private Integer stock;

    private String status;

    /**
     * 加购时间
     */
    private Timestamp createTime;

    private Timestamp updateTime;
}
