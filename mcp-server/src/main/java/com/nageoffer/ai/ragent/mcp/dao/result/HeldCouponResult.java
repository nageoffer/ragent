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
 * 持券关系与券模板的合并视图
 * <p>
 * heldStatus 只说「有没有被用掉」，过没过期一律按当前时间现判，不落库
 */
@Data
public class HeldCouponResult {

    private String couponCode;

    private String name;

    private String type;

    private BigDecimal threshold;

    private BigDecimal discountValue;

    private String category;

    private Timestamp validFrom;

    private Timestamp validTo;

    private String heldStatus;

    private String orderNo;

    private Timestamp useTime;
}
