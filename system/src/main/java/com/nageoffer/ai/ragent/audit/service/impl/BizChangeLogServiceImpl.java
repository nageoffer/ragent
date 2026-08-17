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

package com.nageoffer.ai.ragent.audit.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.nageoffer.ai.ragent.audit.controller.request.BizChangeLogPageRequest;
import com.nageoffer.ai.ragent.audit.controller.vo.BizChangeLogVO;
import com.nageoffer.ai.ragent.audit.dao.entity.BizChangeLogDO;
import com.nageoffer.ai.ragent.audit.dao.mapper.BizChangeLogMapper;
import com.nageoffer.ai.ragent.audit.service.BizChangeLogService;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class BizChangeLogServiceImpl implements BizChangeLogService {

    private final BizChangeLogMapper bizChangeLogMapper;

    @Override
    public IPage<BizChangeLogVO> page(BizChangeLogPageRequest requestParam) {
        Page<BizChangeLogDO> page = new Page<>(requestParam.getCurrent(), requestParam.getSize());
        LambdaQueryWrapper<BizChangeLogDO> queryWrapper = Wrappers.lambdaQuery(BizChangeLogDO.class)
                .eq(StringUtils.hasText(requestParam.getBizType()), BizChangeLogDO::getBizType, requestParam.getBizType())
                .like(StringUtils.hasText(requestParam.getBizId()), BizChangeLogDO::getBizId, requestParam.getBizId())
                .eq(StringUtils.hasText(requestParam.getOperationType()), BizChangeLogDO::getOperationType, requestParam.getOperationType())
                .eq(StringUtils.hasText(requestParam.getOperatorId()), BizChangeLogDO::getOperatorId, requestParam.getOperatorId())
                .like(StringUtils.hasText(requestParam.getOperatorName()), BizChangeLogDO::getOperatorName, requestParam.getOperatorName())
                .eq(requestParam.getSuccess() != null, BizChangeLogDO::getSuccess, requestParam.getSuccess())
                .ge(requestParam.getBeginTime() != null, BizChangeLogDO::getCreateTime, requestParam.getBeginTime())
                .le(requestParam.getEndTime() != null, BizChangeLogDO::getCreateTime, requestParam.getEndTime())
                .orderByDesc(BizChangeLogDO::getCreateTime);
        return bizChangeLogMapper.selectPage(page, queryWrapper)
                .convert(each -> BeanUtil.toBean(each, BizChangeLogVO.class));
    }

    @Override
    public BizChangeLogVO get(String id) {
        BizChangeLogDO record = bizChangeLogMapper.selectById(id);
        if (record == null) {
            throw new ClientException("变更审计日志不存在");
        }
        return BeanUtil.toBean(record, BizChangeLogVO.class);
    }
}
