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

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mzt.logapi.beans.CodeVariableType;
import com.mzt.logapi.beans.LogRecord;
import com.mzt.logapi.service.ILogRecordService;
import com.nageoffer.ai.ragent.audit.dao.entity.BizChangeLogDO;
import com.nageoffer.ai.ragent.audit.dao.mapper.BizChangeLogMapper;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class BizChangeLogRecordService implements ILogRecordService {

    private static final String UNKNOWN_BIZ_ID = "UNKNOWN";

    private final BizChangeLogMapper bizChangeLogMapper;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(LogRecord logRecord) {
        JsonNode extra = parseExtra(logRecord.getExtra());
        HttpServletRequest request = currentRequest();
        BizChangeLogDO record = BizChangeLogDO.builder()
                .bizType(limit(logRecord.getType(), 64))
                .bizId(limit(StringUtils.hasText(logRecord.getBizNo()) ? logRecord.getBizNo() : UNKNOWN_BIZ_ID, 64))
                .operationType(limit(logRecord.getSubType(), 32))
                .actionDesc(limit(logRecord.getAction(), 512))
                .beforeSnapshot(jsonNodeToString(extra.get("beforeSnapshot")))
                .afterSnapshot(jsonNodeToString(extra.get("afterSnapshot")))
                .changeDiff(jsonNodeToString(extra.get("changeDiff")))
                .operatorId(limit(resolveOperatorId(logRecord), 64))
                .operatorName(limit(UserContext.getUsername(), 128))
                .operatorRole(limit(UserContext.getRole(), 64))
                .success(!logRecord.isFail())
                .errorMessage(logRecord.isFail() ? logRecord.getAction() : null)
                .className(limit(resolveClassName(logRecord.getCodeVariable()), 255))
                .methodName(limit(resolveMethodName(logRecord.getCodeVariable()), 255))
                .ip(limit(resolveIp(request), 64))
                .userAgent(limit(request == null ? null : request.getHeader("User-Agent"), 512))
                .createTime(logRecord.getCreateTime())
                .build();
        bizChangeLogMapper.insert(record);
    }

    @Override
    public List<LogRecord> queryLog(String bizNo, String type) {
        return bizChangeLogMapper.selectList(Wrappers.lambdaQuery(BizChangeLogDO.class)
                        .eq(BizChangeLogDO::getBizId, bizNo)
                        .eq(BizChangeLogDO::getBizType, type)
                        .orderByDesc(BizChangeLogDO::getCreateTime)
                        .last("LIMIT 100"))
                .stream()
                .map(this::toLogRecord)
                .toList();
    }

    @Override
    public List<LogRecord> queryLogByBizNo(String bizNo, String type, String subType) {
        return bizChangeLogMapper.selectList(Wrappers.lambdaQuery(BizChangeLogDO.class)
                        .eq(BizChangeLogDO::getBizId, bizNo)
                        .eq(BizChangeLogDO::getBizType, type)
                        .eq(BizChangeLogDO::getOperationType, subType)
                        .orderByDesc(BizChangeLogDO::getCreateTime)
                        .last("LIMIT 100"))
                .stream()
                .map(this::toLogRecord)
                .toList();
    }

    private JsonNode parseExtra(String extra) {
        if (!StringUtils.hasText(extra)) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(extra);
        } catch (JsonProcessingException e) {
            return objectMapper.createObjectNode();
        }
    }

    private String jsonNodeToString(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        return node.toString();
    }

    private String resolveOperatorId(LogRecord logRecord) {
        String userId = UserContext.getUserId();
        if (StringUtils.hasText(userId)) {
            return userId;
        }
        return StringUtils.hasText(logRecord.getOperator()) ? logRecord.getOperator() : "SYSTEM";
    }

    private String resolveClassName(Map<CodeVariableType, Object> codeVariable) {
        if (codeVariable == null || !codeVariable.containsKey(CodeVariableType.ClassName)) {
            return null;
        }
        Object value = codeVariable.get(CodeVariableType.ClassName);
        if (value instanceof Class<?> clazz) {
            return clazz.getName();
        }
        return String.valueOf(value);
    }

    private String resolveMethodName(Map<CodeVariableType, Object> codeVariable) {
        if (codeVariable == null || !codeVariable.containsKey(CodeVariableType.MethodName)) {
            return null;
        }
        Object value = codeVariable.get(CodeVariableType.MethodName);
        return value == null ? null : String.valueOf(value);
    }

    private HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            return attributes.getRequest();
        }
        return null;
    }

    private String resolveIp(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String forwardedFor = firstHeaderValue(request.getHeader("X-Forwarded-For"));
        if (StringUtils.hasText(forwardedFor)) {
            return forwardedFor;
        }
        String realIp = request.getHeader("X-Real-IP");
        return StringUtils.hasText(realIp) ? realIp : request.getRemoteAddr();
    }

    private String firstHeaderValue(String headerValue) {
        if (!StringUtils.hasText(headerValue)) {
            return null;
        }
        int commaIndex = headerValue.indexOf(',');
        return commaIndex >= 0 ? headerValue.substring(0, commaIndex).trim() : headerValue.trim();
    }

    private String limit(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private LogRecord toLogRecord(BizChangeLogDO record) {
        return LogRecord.builder()
                .id(record.getId())
                .type(record.getBizType())
                .bizNo(record.getBizId())
                .subType(record.getOperationType())
                .operator(record.getOperatorId())
                .action(record.getActionDesc())
                .fail(!Boolean.TRUE.equals(record.getSuccess()))
                .createTime(record.getCreateTime())
                .build();
    }
}
