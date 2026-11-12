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
import com.nageoffer.ai.ragent.mcp.dao.handler.JsonbStringTypeHandler;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.sql.Timestamp;

/**
 * 商品款（SPU），一款对应知识库里的一篇商品详情
 * <p>
 * 对应 t_product
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName(value = "t_product", autoResultMap = true)
public class ProductDO {

    /**
     * 主键
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 款编码，与知识库商品详情文档同源命名
     */
    private String spuCode;

    /**
     * 款名称
     */
    private String name;

    /**
     * 一级品类
     */
    private String category;

    /**
     * 二级品类
     */
    private String subCategory;

    /**
     * 品牌
     */
    private String brand;

    /**
     * 品牌归属，只有授权品牌与第三方品牌两种
     */
    private String brandOwner;

    /**
     * 在售或已下架，整款下架才落这里
     */
    private String status;

    /**
     * 款级公共属性，如 {"屏幕":"6.3 英寸"}。不随容量颜色变化的规格都归这里
     */
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String specs;

    /**
     * 标签，逗号分隔
     */
    private String tags;

    /**
     * 一句话卖点，配件不写
     */
    private String sellingPoint;

    /**
     * 创建时间
     */
    @TableField(fill = FieldFill.INSERT)
    private Timestamp createTime;

    /**
     * 更新时间
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Timestamp updateTime;
}
