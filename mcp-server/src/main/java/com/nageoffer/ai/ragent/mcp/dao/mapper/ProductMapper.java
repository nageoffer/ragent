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
import com.nageoffer.ai.ragent.mcp.dao.entity.ProductDO;
import com.nageoffer.ai.ragent.mcp.dao.result.ProductSummaryResult;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.util.List;

/**
 * 商品款（SPU）
 * <p>
 * 浏览一律按款出，同一款的各容量各颜色是一条而不是十六条；要具体配置走 ProductSkuMapper
 */
public interface ProductMapper extends BaseMapper<ProductDO> {

    /**
     * 价格条件作用在子查询里的单个配置上，所以「5000 到 8000 的平板」能命中起售 3799、
     * 但有 5999 配置的那款，回显的区间也一定落在用户要的范围内
     * <p>
     * 排序先按「是不是配件」分层：配件名里普遍带着适配机型（「适用于 iPhone 17」），
     * 而配件数量是设备的二十倍，不分层的话问「iPhone 17 怎么样」会先返回十个保护壳。
     * 两类都留在结果里，只是设备在前——按品类筛选时这一层是空操作
     */
    @Select("""
            <script>
            SELECT p.spu_code, p.name, p.category, p.sub_category, p.brand, p.brand_owner,
                   p.specs::text AS specs, p.tags, p.selling_point,
                   a.sku_count, a.stock, a.min_price, a.max_price
            FROM t_product p
                     JOIN (SELECT spu_code,
                                  COUNT(*)   AS sku_count,
                                  SUM(stock) AS stock,
                                  MIN(price) AS min_price,
                                  MAX(price) AS max_price
                           FROM t_product_sku
                           WHERE status = '在售'
                           <if test="minPrice != null"> AND price &gt;= #{minPrice}</if>
                           <if test="maxPrice != null"> AND price &lt;= #{maxPrice}</if>
                           GROUP BY spu_code) a ON a.spu_code = p.spu_code
            WHERE p.status = '在售'
            <if test="category != null"> AND p.category = #{category}</if>
            <if test="keyword != null">
              AND (p.name ILIKE '%' || #{keyword} || '%' OR p.sub_category ILIKE '%' || #{keyword} || '%'
                   OR p.brand ILIKE '%' || #{keyword} || '%' OR p.tags ILIKE '%' || #{keyword} || '%'
                   OR p.selling_point ILIKE '%' || #{keyword} || '%')
            </if>
            <foreach item="tag" collection="tags">
              AND p.tags ILIKE '%' || #{tag} || '%'
            </foreach>
            ORDER BY CASE WHEN p.category = '配件' THEN 1 ELSE 0 END,
                     a.min_price <if test="priceDesc">DESC</if><if test="!priceDesc">ASC</if>, p.spu_code
            LIMIT #{limit}
            </script>
            """)
    List<ProductSummaryResult> selectSummaries(@Param("category") String category,
                                               @Param("keyword") String keyword,
                                               @Param("tags") List<String> tags,
                                               @Param("minPrice") BigDecimal minPrice,
                                               @Param("maxPrice") BigDecimal maxPrice,
                                               @Param("priceDesc") boolean priceDesc,
                                               @Param("limit") int limit);
}
