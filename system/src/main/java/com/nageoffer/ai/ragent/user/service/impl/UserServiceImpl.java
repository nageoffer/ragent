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

package com.nageoffer.ai.ragent.user.service.impl;

import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.mzt.logapi.starter.annotation.LogRecord;
import com.nageoffer.ai.ragent.audit.constant.BizChangeBizType;
import com.nageoffer.ai.ragent.audit.constant.BizChangeOperationType;
import com.nageoffer.ai.ragent.audit.support.BizChangeLogContext;
import com.nageoffer.ai.ragent.framework.context.LoginUser;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.user.controller.request.ChangePasswordRequest;
import com.nageoffer.ai.ragent.user.controller.request.UserCreateRequest;
import com.nageoffer.ai.ragent.user.controller.request.UserPageRequest;
import com.nageoffer.ai.ragent.user.controller.request.UserUpdateRequest;
import com.nageoffer.ai.ragent.user.controller.vo.UserVO;
import com.nageoffer.ai.ragent.user.dao.entity.UserDO;
import com.nageoffer.ai.ragent.user.dao.mapper.UserMapper;
import com.nageoffer.ai.ragent.user.enums.UserRole;
import com.nageoffer.ai.ragent.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private static final String DEFAULT_ADMIN_USERNAME = "admin";

    private final UserMapper userMapper;
    private final BizChangeLogContext bizChangeLogContext;

    @Override
    public IPage<UserVO> pageQuery(UserPageRequest requestParam) {
        String keyword = StrUtil.trimToNull(requestParam.getKeyword());
        Page<UserDO> page = new Page<>(requestParam.getCurrent(), requestParam.getSize());
        IPage<UserDO> result = userMapper.selectPage(
                page,
                Wrappers.lambdaQuery(UserDO.class)
                        .eq(UserDO::getDeleted, 0)
                        .and(StrUtil.isNotBlank(keyword), wrapper -> wrapper
                                .like(UserDO::getUsername, keyword)
                                .or()
                                .like(UserDO::getRole, keyword))
                        .orderByDesc(UserDO::getUpdateTime)
        );
        return result.convert(this::toVO);
    }

    @Override
    @LogRecord(
            success = "创建用户：{{#requestParam.username}}",
            fail = "创建用户失败：{{#_errorMsg}}",
            type = BizChangeBizType.USER,
            subType = BizChangeOperationType.CREATE,
            bizNo = BizChangeLogContext.BIZ_ID_EXPRESSION,
            extra = BizChangeLogContext.SNAPSHOT_EXPRESSION,
            condition = BizChangeLogContext.RECORD_CONDITION
    )
    public String create(UserCreateRequest requestParam) {
        Assert.notNull(requestParam, () -> new ClientException("请求不能为空"));
        String username = StrUtil.trimToNull(requestParam.getUsername());
        String password = StrUtil.trimToNull(requestParam.getPassword());
        String role = StrUtil.trimToNull(requestParam.getRole());
        Assert.notBlank(username, () -> new ClientException("用户名不能为空"));
        Assert.notBlank(password, () -> new ClientException("密码不能为空"));

        if (DEFAULT_ADMIN_USERNAME.equalsIgnoreCase(username)) {
            throw new ClientException("默认管理员用户名不可用");
        }
        role = normalizeRole(role);
        ensureUsernameAvailable(username, null);

        UserDO record = UserDO.builder()
                .username(username)
                .password(password)
                .role(role)
                .avatar(StrUtil.trimToNull(requestParam.getAvatar()))
                .build();
        userMapper.insert(record);
        bizChangeLogContext.put(String.valueOf(record.getId()), null, toVO(record));
        return String.valueOf(record.getId());
    }

    @Override
    @LogRecord(
            success = "更新用户：{{#id}}",
            fail = "更新用户失败：{{#_errorMsg}}",
            type = BizChangeBizType.USER,
            subType = BizChangeOperationType.UPDATE,
            bizNo = "{{#id}}",
            extra = BizChangeLogContext.SNAPSHOT_EXPRESSION,
            condition = BizChangeLogContext.RECORD_CONDITION
    )
    public void update(String id, UserUpdateRequest requestParam) {
        Assert.notNull(requestParam, () -> new ClientException("请求不能为空"));
        UserDO record = loadById(id);
        ensureNotDefaultAdmin(record);
        UserVO before = toVO(record);

        if (requestParam.getUsername() != null) {
            String username = StrUtil.trimToNull(requestParam.getUsername());
            Assert.notBlank(username, () -> new ClientException("用户名不能为空"));
            if (!username.equals(record.getUsername())) {
                if (DEFAULT_ADMIN_USERNAME.equalsIgnoreCase(username)) {
                    throw new ClientException("默认管理员用户名不可用");
                }
                ensureUsernameAvailable(username, record.getId());
            }
            record.setUsername(username);
        }

        if (requestParam.getRole() != null) {
            record.setRole(normalizeRole(requestParam.getRole()));
        }

        if (requestParam.getAvatar() != null) {
            record.setAvatar(StrUtil.trimToNull(requestParam.getAvatar()));
        }

        if (requestParam.getPassword() != null) {
            String password = StrUtil.trimToNull(requestParam.getPassword());
            Assert.notBlank(password, () -> new ClientException("新密码不能为空"));
            record.setPassword(password);
        }

        userMapper.updateById(record);
        bizChangeLogContext.put(id, before, toVO(userMapper.selectById(id)));
    }

    @Override
    @LogRecord(
            success = "删除用户：{{#id}}",
            fail = "删除用户失败：{{#_errorMsg}}",
            type = BizChangeBizType.USER,
            subType = BizChangeOperationType.DELETE,
            bizNo = "{{#id}}",
            extra = BizChangeLogContext.SNAPSHOT_EXPRESSION,
            condition = BizChangeLogContext.RECORD_CONDITION
    )
    public void delete(String id) {
        UserDO record = loadById(id);
        ensureNotDefaultAdmin(record);
        UserVO before = toVO(record);
        userMapper.deleteById(record.getId());
        bizChangeLogContext.put(id, before, null);
    }

    @Override
    @LogRecord(
            success = "修改当前用户密码",
            fail = "修改当前用户密码失败：{{#_errorMsg}}",
            type = BizChangeBizType.USER,
            subType = BizChangeOperationType.UPDATE,
            bizNo = "{{T(com.nageoffer.ai.ragent.framework.context.UserContext).getUserId()}}",
            extra = BizChangeLogContext.SNAPSHOT_EXPRESSION,
            condition = BizChangeLogContext.RECORD_CONDITION
    )
    public void changePassword(ChangePasswordRequest requestParam) {
        Assert.notNull(requestParam, () -> new ClientException("请求不能为空"));
        String current = StrUtil.trimToNull(requestParam.getCurrentPassword());
        String next = StrUtil.trimToNull(requestParam.getNewPassword());
        Assert.notBlank(current, () -> new ClientException("当前密码不能为空"));
        Assert.notBlank(next, () -> new ClientException("新密码不能为空"));

        LoginUser loginUser = UserContext.requireUser();
        UserDO record = userMapper.selectOne(
                Wrappers.lambdaQuery(UserDO.class)
                        .eq(UserDO::getId, loginUser.getUserId())
                        .eq(UserDO::getDeleted, 0)
        );
        Assert.notNull(record, () -> new ClientException("用户不存在"));
        UserVO before = toVO(record);
        if (!passwordMatches(current, record.getPassword())) {
            throw new ClientException("当前密码不正确");
        }
        record.setPassword(next);
        userMapper.updateById(record);
        bizChangeLogContext.put(loginUser.getUserId(), before, toVO(userMapper.selectById(loginUser.getUserId())));
    }

    private UserDO loadById(String id) {
        UserDO record = userMapper.selectOne(
                Wrappers.lambdaQuery(UserDO.class)
                        .eq(UserDO::getId, id)
                        .eq(UserDO::getDeleted, 0)
        );
        Assert.notNull(record, () -> new ClientException("用户不存在"));
        return record;
    }

    private void ensureNotDefaultAdmin(UserDO record) {
        if (record != null && DEFAULT_ADMIN_USERNAME.equalsIgnoreCase(record.getUsername())) {
            throw new ClientException("默认管理员不允许修改或删除");
        }
    }

    private void ensureUsernameAvailable(String username, String excludeId) {
        UserDO existing = userMapper.selectOne(
                Wrappers.lambdaQuery(UserDO.class)
                        .eq(UserDO::getUsername, username)
                        .eq(UserDO::getDeleted, 0)
                        .ne(excludeId != null, UserDO::getId, excludeId)
        );
        if (existing != null) {
            throw new ClientException("用户名已存在");
        }
    }

    private String normalizeRole(String role) {
        String value = StrUtil.trimToNull(role);
        if (StrUtil.isBlank(value)) {
            return UserRole.USER.getCode();
        }
        if (UserRole.ADMIN.getCode().equalsIgnoreCase(value)) {
            return UserRole.ADMIN.getCode();
        }
        if (UserRole.USER.getCode().equalsIgnoreCase(value)) {
            return UserRole.USER.getCode();
        }
        throw new ClientException("角色类型不合法");
    }

    private boolean passwordMatches(String input, String stored) {
        if (stored == null) {
            return input == null;
        }
        return stored.equals(input);
    }

    private UserVO toVO(UserDO record) {
        return UserVO.builder()
                .id(String.valueOf(record.getId()))
                .username(record.getUsername())
                .role(record.getRole())
                .avatar(record.getAvatar())
                .createTime(record.getCreateTime())
                .updateTime(record.getUpdateTime())
                .build();
    }
}
