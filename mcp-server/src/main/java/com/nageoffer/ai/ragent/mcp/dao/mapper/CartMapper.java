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
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.nageoffer.ai.ragent.mcp.dao.entity.CartDO;
import com.nageoffer.ai.ragent.mcp.dao.result.CartLineResult;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.List;

/**
 * 购物车
 * 新增按增量累加，减少最低保留一件
 */
public interface CartMapper extends BaseMapper<CartDO> {

    String LINE_SQL = """
            SELECT c.id, c.user_id, c.sku_code, c.quantity, c.added_price, c.create_time, c.update_time,
                   s.name AS sku_name, s.price, s.stock, s.status, p.category
            FROM t_cart c
                     JOIN t_product_sku s ON s.sku_code = c.sku_code
                     JOIN t_product p ON p.spu_code = s.spu_code
            WHERE c.user_id = #{userId}
            """;

    @Select(LINE_SQL + " ORDER BY c.create_time DESC")
    List<CartLineResult> selectLines(@Param("userId") String userId);

    /**
     * 下单用：对购物车行加锁后再读，同一用户并发下单时后到的那笔等在这里
     * <p>
     * FOR UPDATE OF c 只锁购物车不锁商品，锁住商品会把并发下单串行成一条队
     */
    @Select("""
            <script>
            """ + LINE_SQL + """
            <if test="skuCodes != null and skuCodes.size() > 0">
              AND c.sku_code IN <foreach item="code" collection="skuCodes" open="(" separator="," close=")">#{code}</foreach>
            </if>
            ORDER BY c.create_time
            FOR UPDATE OF c
            </script>
            """)
    List<CartLineResult> selectLinesForUpdate(@Param("userId") String userId,
                                              @Param("skuCodes") List<String> skuCodes);

    /**
     * 下单事务已锁定该行，只扣本次购买数量；数量归零后由执行器删除。
     */
    @Update("""
            UPDATE t_cart SET quantity = quantity - #{quantity}, update_time = now()
            WHERE user_id = #{userId} AND sku_code = #{skuCode} AND quantity >= #{quantity}
            """)
    int deductQuantity(@Param("userId") String userId, @Param("skuCode") String skuCode,
                       @Param("quantity") int quantity);

    /**
     * 在数据库内原子累加，避免先查再写丢失并发加购；达到上限时不更新，返回 null
     * RETURNING 返回本次写入后的数量，冲突更新保留最初的加购价格与时间
     */
    @Select(value = """
            INSERT INTO t_cart (id, user_id, sku_code, quantity, added_price, create_time, update_time)
            VALUES (#{id}, #{userId}, #{skuCode}, #{quantity}, #{addedPrice}, #{now}, #{now})
            ON CONFLICT (user_id, sku_code)
            DO UPDATE SET quantity = t_cart.quantity + EXCLUDED.quantity, update_time = #{now}
            WHERE t_cart.quantity + EXCLUDED.quantity <= #{maxQuantity}
            RETURNING quantity
            """, affectData = true)
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    Integer addQuantity(@Param("id") long id, @Param("userId") String userId, @Param("skuCode") String skuCode,
                        @Param("quantity") int quantity, @Param("addedPrice") BigDecimal addedPrice,
                        @Param("maxQuantity") int maxQuantity, @Param("now") Timestamp now);

    default Integer addQuantity(String userId, String skuCode, int quantity, BigDecimal addedPrice, int maxQuantity) {
        return addQuantity(IdWorker.getId(), userId, skuCode, quantity, addedPrice, maxQuantity,
                new Timestamp(System.currentTimeMillis()));
    }

    /**
     * 减少购物车数量，最低保留一件；不存在时返回 null
     */
    @Select(value = """
            UPDATE t_cart SET quantity = GREATEST(quantity - #{quantity}, 1), update_time = now()
            WHERE user_id = #{userId} AND sku_code = #{skuCode}
            RETURNING quantity
            """, affectData = true)
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    Integer decreaseQuantity(@Param("userId") String userId, @Param("skuCode") String skuCode,
                             @Param("quantity") int quantity);
}
