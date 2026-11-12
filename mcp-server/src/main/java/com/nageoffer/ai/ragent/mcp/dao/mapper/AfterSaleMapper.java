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

package com.nageoffer.ai.ragent.mcp.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.nageoffer.ai.ragent.mcp.dao.entity.AfterSaleDO;
import com.nageoffer.ai.ragent.mcp.dao.result.AfterSaleDetailResult;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 售后单
 * <p>
 * 不回写订单状态：一单多件只退其中一件时，把整单履约状态覆盖成退款中是错的，
 * 那件商品的物流还在走。售后状态由本表独立表达
 */
public interface AfterSaleMapper extends BaseMapper<AfterSaleDO> {

    /**
     * 联查订单归属，不能只凭售后单号取
     */
    @Select("""
            <script>
            SELECT a.after_sale_no, a.order_no, a.sku_code, i.sku_name, a.type, a.reason,
                   a.status, a.create_time, a.update_time, a.finish_time
            FROM t_after_sale a
                     JOIN t_order o ON o.order_no = a.order_no
                     LEFT JOIN t_order_item i ON i.order_no = a.order_no AND i.sku_code = a.sku_code
            WHERE o.user_id = #{userId}
            <if test="afterSaleNo != null"> AND a.after_sale_no = #{afterSaleNo}</if>
            <if test="orderNo != null"> AND a.order_no = #{orderNo}</if>
            ORDER BY a.create_time DESC
            LIMIT #{limit}
            </script>
            """)
    List<AfterSaleDetailResult> selectDetails(@Param("userId") String userId,
                                              @Param("afterSaleNo") String afterSaleNo,
                                              @Param("orderNo") String orderNo,
                                              @Param("limit") int limit);
}
