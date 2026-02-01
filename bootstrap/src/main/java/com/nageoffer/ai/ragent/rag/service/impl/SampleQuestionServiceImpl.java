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

package com.nageoffer.ai.ragent.rag.service.impl;

import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.rag.controller.request.SampleQuestionCreateRequest;
import com.nageoffer.ai.ragent.rag.controller.request.SampleQuestionPageRequest;
import com.nageoffer.ai.ragent.rag.controller.request.SampleQuestionUpdateRequest;
import com.nageoffer.ai.ragent.rag.controller.vo.SampleQuestionVO;
import com.nageoffer.ai.ragent.rag.dao.entity.SampleQuestionDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.SampleQuestionMapper;
import com.nageoffer.ai.ragent.rag.service.SampleQuestionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class SampleQuestionServiceImpl implements SampleQuestionService {

    private static final int DEFAULT_LIMIT = 3;

    private final SampleQuestionMapper sampleQuestionMapper;

    @Override
    public String create(SampleQuestionCreateRequest requestParam) {
        Assert.notNull(requestParam, () -> new ClientException("请求不能为空"));
        String question = StrUtil.trimToNull(requestParam.getQuestion());
        Assert.notBlank(question, () -> new ClientException("示例问题内容不能为空"));

        SampleQuestionDO record = SampleQuestionDO.builder()
                .title(StrUtil.trimToNull(requestParam.getTitle()))
                .description(StrUtil.trimToNull(requestParam.getDescription()))
                .question(question)
                .build();
        sampleQuestionMapper.insert(record);
        return String.valueOf(record.getId());
    }

    @Override
    public void update(String id, SampleQuestionUpdateRequest requestParam) {
        Assert.notNull(requestParam, () -> new ClientException("请求不能为空"));
        SampleQuestionDO record = loadById(id);

        if (requestParam.getQuestion() != null) {
            String question = StrUtil.trimToNull(requestParam.getQuestion());
            Assert.notBlank(question, () -> new ClientException("示例问题内容不能为空"));
            record.setQuestion(question);
        }
        if (requestParam.getTitle() != null) {
            record.setTitle(StrUtil.trimToNull(requestParam.getTitle()));
        }
        if (requestParam.getDescription() != null) {
            record.setDescription(StrUtil.trimToNull(requestParam.getDescription()));
        }

        sampleQuestionMapper.updateById(record);
    }

    @Override
    public void delete(String id) {
        SampleQuestionDO record = loadById(id);
        sampleQuestionMapper.deleteById(record.getId());
    }

    @Override
    public SampleQuestionVO queryById(String id) {
        SampleQuestionDO record = loadById(id);
        return toVO(record);
    }

    @Override
    public IPage<SampleQuestionVO> pageQuery(SampleQuestionPageRequest requestParam) {
        String keyword = StrUtil.trimToNull(requestParam.getKeyword());
        Page<SampleQuestionDO> page = new Page<>(requestParam.getCurrent(), requestParam.getSize());
        IPage<SampleQuestionDO> result = sampleQuestionMapper.selectPage(
                page,
                Wrappers.lambdaQuery(SampleQuestionDO.class)
                        .eq(SampleQuestionDO::getDeleted, 0)
                        .and(StrUtil.isNotBlank(keyword), wrapper -> wrapper
                                .like(SampleQuestionDO::getTitle, keyword)
                                .or()
                                .like(SampleQuestionDO::getDescription, keyword)
                                .or()
                                .like(SampleQuestionDO::getQuestion, keyword))
                        .orderByDesc(SampleQuestionDO::getUpdateTime)
        );
        return result.convert(this::toVO);
    }

    @Override
    public List<SampleQuestionVO> listRandomQuestions() {
        List<SampleQuestionDO> records = sampleQuestionMapper.selectList(
                Wrappers.lambdaQuery(SampleQuestionDO.class)
                        .eq(SampleQuestionDO::getDeleted, 0)
                        .last("ORDER BY RAND() LIMIT " + DEFAULT_LIMIT)
        );
        if (records == null || records.isEmpty()) {
            return List.of();
        }
        return records.stream()
                .map(this::toVO)
                .toList();
    }

    private SampleQuestionDO loadById(String id) {
        Long parsedId = parseId(id);
        SampleQuestionDO record = sampleQuestionMapper.selectOne(
                Wrappers.lambdaQuery(SampleQuestionDO.class)
                        .eq(SampleQuestionDO::getId, parsedId)
                        .eq(SampleQuestionDO::getDeleted, 0)
        );
        Assert.notNull(record, () -> new ClientException("示例问题不存在"));
        return record;
    }

    private Long parseId(String id) {
        if (StrUtil.isBlank(id)) {
            throw new ClientException("示例问题ID不能为空");
        }
        try {
            return Long.parseLong(id);
        } catch (NumberFormatException ex) {
            throw new ClientException("示例问题ID非法");
        }
    }

    private SampleQuestionVO toVO(SampleQuestionDO record) {
        return SampleQuestionVO.builder()
                .id(String.valueOf(record.getId()))
                .title(record.getTitle())
                .description(record.getDescription())
                .question(record.getQuestion())
                .createTime(record.getCreateTime())
                .updateTime(record.getUpdateTime())
                .build();
    }
}
