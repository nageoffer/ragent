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
import com.nageoffer.ai.ragent.mcp.dao.entity.ProductSkuDO;
import com.nageoffer.ai.ragent.mcp.dao.result.SkuDetailResult;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 可下单配置（SKU），下单、加购、扣库存都落在这一层
 */
public interface ProductSkuMapper extends BaseMapper<ProductSkuDO> {

    /**
     * 点名要的一律照给，包括已下架：「这款还能不能买」得看得见下架状态才答得了
     */
    @Select("""
            <script>
            SELECT s.sku_code, s.spu_code, p.name AS spu_name, s.name, p.category, p.sub_category,
                   s.specs::text AS specs, p.specs::text AS spu_specs, s.price, s.stock, s.status
            FROM t_product_sku s
                     JOIN t_product p ON p.spu_code = s.spu_code
            WHERE
            <choose>
              <when test="spuCodes != null and spuCodes.size() > 0">
                s.spu_code IN <foreach item="c" collection="spuCodes" open="(" separator="," close=")">#{c}</foreach>
              </when>
              <otherwise>
                s.sku_code IN <foreach item="c" collection="skuCodes" open="(" separator="," close=")">#{c}</foreach>
              </otherwise>
            </choose>
            ORDER BY s.spu_code, s.price <if test="priceDesc">DESC</if><if test="!priceDesc">ASC</if>, s.sku_code
            LIMIT #{limit}
            </script>
            """)
    List<SkuDetailResult> selectDetails(@Param("spuCodes") List<String> spuCodes,
                                        @Param("skuCodes") List<String> skuCodes,
                                        @Param("priceDesc") boolean priceDesc,
                                        @Param("limit") int limit);

    /**
     * 库存靠受影响行数判，先查再减在并发下必然超卖
     *
     * @return 0 即库存不足或已下架，整笔要回滚
     */
    @Update("""
            UPDATE t_product_sku SET stock = stock - #{quantity}, update_time = now()
            WHERE sku_code = #{skuCode} AND status = '在售' AND stock >= #{quantity}
            """)
    int deductStock(@Param("skuCode") String skuCode, @Param("quantity") int quantity);

    /**
     * 按订单行把库存加回去，一单多件也只走这一条
     */
    @Update("""
            UPDATE t_product_sku s SET stock = s.stock + i.quantity, update_time = now()
            FROM t_order_item i
            WHERE i.order_no = #{orderNo} AND s.sku_code = i.sku_code
            """)
    int restoreStockByOrder(@Param("orderNo") String orderNo);
}
