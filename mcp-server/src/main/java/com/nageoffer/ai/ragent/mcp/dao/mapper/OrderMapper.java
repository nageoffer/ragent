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

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.nageoffer.ai.ragent.mcp.dao.entity.OrderDO;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 订单头
 * <p>
 * 所有状态迁移一律条件 UPDATE 判受影响行数，这是并发正确性的唯一来源：
 * 支付与超时释放同时发生时，谁的受影响行数是 1 谁就赢，输的一方按 0 行分支给准确回答而不是抛异常
 */
public interface OrderMapper extends BaseMapper<OrderDO> {

    /**
     * 一律带 user_id 条件：无权访问与不存在都返回 null，调用方回同一句「未找到」，
     * 否则返回文案本身就成了一个存在性探针
     */
    default OrderDO selectByNo(String orderNo, String userId) {
        return selectOne(Wrappers.<OrderDO>lambdaQuery()
                .eq(OrderDO::getOrderNo, orderNo)
                .eq(OrderDO::getUserId, userId));
    }

    default OrderDO selectByTrackingNo(String trackingNo, String userId) {
        return selectOne(Wrappers.<OrderDO>lambdaQuery()
                .eq(OrderDO::getTrackingNo, trackingNo)
                .eq(OrderDO::getUserId, userId));
    }

    default List<OrderDO> selectRecent(String userId, int limit) {
        return selectList(Wrappers.<OrderDO>lambdaQuery()
                .eq(OrderDO::getUserId, userId)
                .orderByDesc(OrderDO::getCreateTime)
                .last("LIMIT " + limit));
    }

    /**
     * 不传收货信息时沿用最近一笔有地址的订单
     */
    default OrderDO selectLatestReceiver(String userId) {
        return selectOne(Wrappers.<OrderDO>lambdaQuery()
                .eq(OrderDO::getUserId, userId)
                .isNotNull(OrderDO::getReceiverName)
                .isNotNull(OrderDO::getReceiverAddress)
                .orderByDesc(OrderDO::getCreateTime)
                .last("LIMIT 1"));
    }

    @Select("""
            SELECT order_no FROM t_order
            WHERE status = '待支付' AND create_time < now() - CAST(#{interval} AS interval)
            ORDER BY create_time
            """)
    List<String> selectExpiredPendingNos(@Param("interval") String interval);

    /**
     * @return 0 即该单已不是待支付，调用方据此分流
     */
    @Update("""
            UPDATE t_order SET status = '已支付待发货', pay_time = now(), update_time = now()
            WHERE order_no = #{orderNo} AND user_id = #{userId} AND status = '待支付'
            """)
    int pay(@Param("orderNo") String orderNo, @Param("userId") String userId);

    /**
     * 主动取消与超时释放共用这一条，两套逻辑一定会漂成「有时候券退回来了有时候没有」
     */
    @Update("""
            UPDATE t_order SET status = '已取消', cancel_time = now(), update_time = now()
            WHERE order_no = #{orderNo} AND status = #{expectedStatus}
            """)
    int cancel(@Param("orderNo") String orderNo, @Param("expectedStatus") String expectedStatus);

    @Update("""
            UPDATE t_order
               SET receiver_name = COALESCE(#{name}, receiver_name),
                   receiver_phone = COALESCE(#{phone}, receiver_phone),
                   receiver_address = COALESCE(#{address}, receiver_address),
                   update_time = now()
             WHERE order_no = #{orderNo} AND user_id = #{userId}
               AND status IN ('待支付', '已支付待发货')
            """)
    int updateReceiver(@Param("name") String name, @Param("phone") String phone,
                       @Param("address") String address, @Param("orderNo") String orderNo,
                       @Param("userId") String userId);
}
