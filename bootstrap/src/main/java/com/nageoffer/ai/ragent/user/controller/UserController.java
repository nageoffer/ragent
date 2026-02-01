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

package com.nageoffer.ai.ragent.user.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.nageoffer.ai.ragent.user.controller.request.ChangePasswordRequest;
import com.nageoffer.ai.ragent.user.controller.request.UserCreateRequest;
import com.nageoffer.ai.ragent.user.controller.request.UserPageRequest;
import com.nageoffer.ai.ragent.user.controller.request.UserUpdateRequest;
import com.nageoffer.ai.ragent.user.controller.vo.CurrentUserVO;
import com.nageoffer.ai.ragent.user.controller.vo.UserVO;
import com.nageoffer.ai.ragent.framework.context.LoginUser;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.convention.Result;
import com.nageoffer.ai.ragent.framework.web.Results;
import com.nageoffer.ai.ragent.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户控制器
 * 提供当前登录用户信息查询接口
 */
@RestController
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    /**
     * 获取当前登录用户信息
     */
    @GetMapping("/user/me")
    public Result<CurrentUserVO> currentUser() {
        LoginUser user = UserContext.requireUser();
        return Results.success(new CurrentUserVO(
                user.getUserId(),
                user.getUsername(),
                user.getRole(),
                user.getAvatar()
        ));
    }

    /**
     * 分页查询用户列表
     */
    @GetMapping("/users")
    public Result<IPage<UserVO>> pageQuery(UserPageRequest requestParam) {
        StpUtil.checkRole("admin");
        return Results.success(userService.pageQuery(requestParam));
    }

    /**
     * 创建用户
     */
    @PostMapping("/users")
    public Result<String> create(@RequestBody UserCreateRequest requestParam) {
        StpUtil.checkRole("admin");
        return Results.success(userService.create(requestParam));
    }

    /**
     * 更新用户
     */
    @PutMapping("/users/{id}")
    public Result<Void> update(@PathVariable String id, @RequestBody UserUpdateRequest requestParam) {
        StpUtil.checkRole("admin");
        userService.update(id, requestParam);
        return Results.success();
    }

    /**
     * 删除用户
     */
    @DeleteMapping("/users/{id}")
    public Result<Void> delete(@PathVariable String id) {
        StpUtil.checkRole("admin");
        userService.delete(id);
        return Results.success();
    }

    /**
     * 修改当前用户密码
     */
    @PutMapping("/user/password")
    public Result<Void> changePassword(@RequestBody ChangePasswordRequest requestParam) {
        userService.changePassword(requestParam);
        return Results.success();
    }
}
