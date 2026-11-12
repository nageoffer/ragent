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

package com.nageoffer.ai.ragent.mcp.executor.bit;

import com.nageoffer.ai.ragent.mcp.config.bit.BitProperties;
import com.nageoffer.ai.ragent.mcp.dao.mapper.OrderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 扫出超时未支付的订单，走取消并归还库存与券
 * <p>
 * 演示态的单机 {@code @Scheduled}：多实例会同时扫，靠 release 的条件 UPDATE 兜住不重复释放，
 * 真要上生产得换成分布式调度或延时消息
 */
@Slf4j
@Component
@EnableScheduling
@RequiredArgsConstructor
public class BitPendingOrderTimeoutTask {

    private final OrderMapper orderMapper;
    private final BitOrderReleaser bitOrderReleaser;
    private final BitProperties bitProperties;

    @Scheduled(fixedDelayString = "${ragent.bit.pending-order.scan-interval:1m}")
    public void releaseExpired() {
        long timeoutSeconds = bitProperties.getPendingOrder().getTimeout().getSeconds();
        try {
            List<String> expired = orderMapper.selectExpiredPendingNos(timeoutSeconds + " seconds");
            if (expired.isEmpty()) {
                return;
            }
            int released = 0;
            for (String orderNo : expired) {
                // 扫到和真取消之间用户可能刚付掉，条件取消抢不到就跳过，不是异常
                if (bitOrderReleaser.release(orderNo, BitOrderReleaser.STATUS_PENDING)) {
                    released++;
                }
            }
            log.info("待支付订单超时释放完成, 扫描={}, 释放={}, 超时阈值={}s", expired.size(), released, timeoutSeconds);
        } catch (Exception e) {
            log.error("待支付订单超时释放失败, 超时阈值={}s", timeoutSeconds, e);
        }
    }
}
