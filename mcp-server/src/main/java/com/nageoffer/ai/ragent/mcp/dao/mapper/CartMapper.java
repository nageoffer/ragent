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
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.List;

/**
 * 购物车
 * <p>
 * 写入定成「设为几件」而不是「加几件」：加购语义重发一次就翻倍，设置语义发多少次结果都一样
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
     * 冲突时只改数量，不动 added_price 与 create_time——加购价被改写，「这件降了多少」就永远算成 0
     */
    @Update("""
            INSERT INTO t_cart (id, user_id, sku_code, quantity, added_price, create_time, update_time)
            VALUES (#{id}, #{userId}, #{skuCode}, #{quantity}, #{addedPrice}, #{now}, #{now})
            ON CONFLICT (user_id, sku_code)
            DO UPDATE SET quantity = EXCLUDED.quantity, update_time = #{now}
            """)
    int upsert(@Param("id") long id, @Param("userId") String userId, @Param("skuCode") String skuCode,
               @Param("quantity") int quantity, @Param("addedPrice") BigDecimal addedPrice,
               @Param("now") Timestamp now);

    /**
     * 自定义 SQL 绕过了 MyBatis-Plus 那套：ASSIGN_ID 与 MetaObjectHandler 都只作用于 BaseMapper 的方法，
     * 所以主键和时间得在这里自己补。撞上 ON CONFLICT 时这个 id 会被丢掉，不影响已有行
     */
    default int upsert(String userId, String skuCode, int quantity, BigDecimal addedPrice) {
        return upsert(IdWorker.getId(), userId, skuCode, quantity, addedPrice,
                new Timestamp(System.currentTimeMillis()));
    }
}
