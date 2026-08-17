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

package com.nageoffer.ai.ragent.knowledge.dao.handler;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.nageoffer.ai.ragent.framework.convention.GroundingChunk;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.postgresql.util.PGobject;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * {@code List<GroundingChunk>} 与 jsonb 列的类型处理器
 * <p>
 * 序列化只发生在 DAO 边界这一处 业务层全程持有 {@code List<GroundingChunk>} 不再手工 JSON 往返
 */
public class GroundingChunkListTypeHandler extends BaseTypeHandler<List<GroundingChunk>> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, List<GroundingChunk> parameter, JdbcType jdbcType) throws SQLException {
        PGobject jsonObject = new PGobject();
        jsonObject.setType("jsonb");
        jsonObject.setValue(JSONUtil.toJsonStr(parameter));
        ps.setObject(i, jsonObject);
    }

    @Override
    public List<GroundingChunk> getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return parse(rs.getString(columnName));
    }

    @Override
    public List<GroundingChunk> getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return parse(rs.getString(columnIndex));
    }

    @Override
    public List<GroundingChunk> getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return parse(cs.getString(columnIndex));
    }

    private List<GroundingChunk> parse(String json) {
        if (StrUtil.isBlank(json)) {
            return null;
        }
        return JSONUtil.toList(json, GroundingChunk.class);
    }
}
