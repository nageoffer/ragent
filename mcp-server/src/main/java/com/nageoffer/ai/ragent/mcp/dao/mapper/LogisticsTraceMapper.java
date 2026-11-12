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
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nageoffer.ai.ragent.mcp.dao.entity.LogisticsTraceDO;

import java.util.List;

/**
 * 物流轨迹
 * <p>
 * 这里只按运单号取轨迹，归属校验在订单那一步做完：只凭运单号就能取到轨迹的话，
 * 运单号本身就成了越权入口
 */
public interface LogisticsTraceMapper extends BaseMapper<LogisticsTraceDO> {

    default List<LogisticsTraceDO> selectByTrackingNo(String trackingNo) {
        return selectList(Wrappers.<LogisticsTraceDO>lambdaQuery()
                .eq(LogisticsTraceDO::getTrackingNo, trackingNo)
                .orderByDesc(LogisticsTraceDO::getTraceTime));
    }
}
