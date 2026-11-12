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

package com.nageoffer.ai.ragent.mcp.dao.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.sql.Timestamp;

/**
 * 订单头
 * <p>
 * 对应 t_order
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_order")
public class OrderDO {

    /**
     * 主键
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 订单号
     */
    private String orderNo;

    /**
     * 平台用户 ID，业务库不设跨库外键
     */
    private String userId;

    /**
     * 订单状态
     */
    private String status;

    /**
     * 商品总额
     */
    private BigDecimal totalAmount;

    /**
     * 优惠额
     */
    private BigDecimal discountAmount;

    /**
     * 实付额
     */
    private BigDecimal payAmount;

    /**
     * 使用的券码
     */
    private String couponCode;

    /**
     * 收货人
     */
    private String receiverName;

    /**
     * 收货手机号，查询工具返回前打码
     */
    private String receiverPhone;

    /**
     * 收货地址，查询工具返回时只到区级
     */
    private String receiverAddress;

    /**
     * 运单号
     */
    private String trackingNo;

    /**
     * 下单时间
     */
    @TableField(fill = FieldFill.INSERT)
    private Timestamp createTime;

    /**
     * 更新时间
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Timestamp updateTime;

    /**
     * 支付时间
     */
    private Timestamp payTime;

    /**
     * 发货时间
     */
    private Timestamp shipTime;

    /**
     * 签收时间，七天无理由窗口按它现算
     */
    private Timestamp receiveTime;

    /**
     * 取消时间
     */
    private Timestamp cancelTime;
}
