package com.nageoffer.ai.ragent.user.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.nageoffer.ai.ragent.audit.support.BizChangeLogContext;
import com.nageoffer.ai.ragent.framework.context.LoginUser;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.user.controller.request.ChangePasswordRequest;
import com.nageoffer.ai.ragent.user.controller.request.UserUpdateRequest;
import com.nageoffer.ai.ragent.user.dao.entity.UserDO;
import com.nageoffer.ai.ragent.user.dao.mapper.UserMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 改密会话失效回归：改密（自助/管理员两路）成功后必须踢该用户全部既有会话——
 * 密码轮换是「疑似 token 泄露」的止损动作，旧 token 存活即止损失效。
 */
class UserServiceImplTest {

    private UserMapper userMapper;
    private UserServiceImpl userService;
    private MockedStatic<StpUtil> stpUtil;

    @BeforeEach
    void setUp() {
        userMapper = mock(UserMapper.class);
        userService = new UserServiceImpl(userMapper, mock(BizChangeLogContext.class));
        stpUtil = mockStatic(StpUtil.class);
        UserContext.set(LoginUser.builder().userId("100").username("alice").role("user").build());
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
        stpUtil.close();
    }

    private UserDO user() {
        UserDO user = new UserDO();
        user.setId("100");
        user.setUsername("alice");
        user.setPassword("right-pass");
        return user;
    }

    @Test
    void 自助改密成功踢该用户全部会话() {
        UserDO record = user();
        when(userMapper.selectOne(any())).thenReturn(record);
        when(userMapper.selectById("100")).thenReturn(record);

        ChangePasswordRequest request = new ChangePasswordRequest();
        request.setCurrentPassword("right-pass");
        request.setNewPassword("new-strong-pass");
        userService.changePassword(request);

        verify(userMapper).updateById(any(UserDO.class));
        stpUtil.verify(() -> StpUtil.logout("100"));
    }

    @Test
    void 自助改密当前密码错误不落库不踢会话() {
        when(userMapper.selectOne(any())).thenReturn(user());

        ChangePasswordRequest request = new ChangePasswordRequest();
        request.setCurrentPassword("wrong-pass");
        request.setNewPassword("new-strong-pass");

        assertThrows(ClientException.class, () -> userService.changePassword(request));
        verify(userMapper, never()).updateById(any(UserDO.class));
        stpUtil.verifyNoInteractions();
    }

    @Test
    void 管理员改密同口径踢全端_非密码更新不踢() {
        UserDO target = user();
        target.setId("200");
        when(userMapper.selectOne(any())).thenReturn(target);
        when(userMapper.selectById("200")).thenReturn(target);

        UserUpdateRequest withPassword = new UserUpdateRequest();
        withPassword.setPassword("admin-set-pass");
        userService.update("200", withPassword);
        stpUtil.verify(() -> StpUtil.logout("200"));

        stpUtil.clearInvocations();
        UserUpdateRequest onlyAvatar = new UserUpdateRequest();
        onlyAvatar.setAvatar("https://cdn.example.com/a.png");
        userService.update("200", onlyAvatar);
        stpUtil.verifyNoInteractions();
    }
}
