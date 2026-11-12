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
import com.nageoffer.ai.ragent.mcp.dao.entity.UserCouponDO;
import com.nageoffer.ai.ragent.mcp.dao.result.HeldCouponResult;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 用户持券关系
 * <p>
 * 查询工具的试算和下单时的实扣共用同一份取数：分成两套迟早会漂成
 * 「查的时候说能用，下单说不能用」，而那种不一致最难查
 */
public interface UserCouponMapper extends BaseMapper<UserCouponDO> {

    String HELD_SQL = """
            SELECT uc.status AS held_status, uc.order_no, uc.use_time,
                   c.coupon_code, c.name, c.type, c.threshold, c.discount_value, c.category,
                   c.valid_from, c.valid_to
            FROM t_user_coupon uc
                     JOIN t_coupon c ON c.coupon_code = uc.coupon_code
            WHERE uc.user_id = #{userId}
            """;

    @Select(HELD_SQL + " ORDER BY c.valid_to")
    List<HeldCouponResult> selectHeld(@Param("userId") String userId);

    @Select(HELD_SQL + " AND uc.coupon_code = #{couponCode}")
    HeldCouponResult selectHeldOne(@Param("userId") String userId, @Param("couponCode") String couponCode);

    /**
     * 核销靠受影响行数判，0 行意味着这张券刚被另一单用掉
     */
    @Update("""
            UPDATE t_user_coupon SET status = '已使用', order_no = #{orderNo},
                   use_time = now(), update_time = now()
            WHERE user_id = #{userId} AND coupon_code = #{couponCode} AND status = '未使用'
            """)
    int use(@Param("orderNo") String orderNo, @Param("userId") String userId,
            @Param("couponCode") String couponCode);

    @Update("""
            UPDATE t_user_coupon SET status = '未使用', order_no = NULL, use_time = NULL, update_time = now()
            WHERE order_no = #{orderNo} AND status = '已使用'
            """)
    int restoreByOrder(@Param("orderNo") String orderNo);
}
