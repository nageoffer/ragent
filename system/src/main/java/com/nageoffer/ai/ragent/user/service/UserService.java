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

package com.nageoffer.ai.ragent.user.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.nageoffer.ai.ragent.user.controller.request.ChangePasswordRequest;
import com.nageoffer.ai.ragent.user.controller.request.UserCreateRequest;
import com.nageoffer.ai.ragent.user.controller.request.UserPageRequest;
import com.nageoffer.ai.ragent.user.controller.request.UserUpdateRequest;
import com.nageoffer.ai.ragent.user.controller.vo.UserVO;

public interface UserService {

    /**
     * 分页查询用户列表
     */
    IPage<UserVO> pageQuery(UserPageRequest requestParam);

    /**
     * 创建用户
     */
    String create(UserCreateRequest requestParam);

    /**
     * 更新用户
     */
    void update(String id, UserUpdateRequest requestParam);

    /**
     * 删除用户
     */
    void delete(String id);

    /**
     * 修改当前用户密码
     */
    void changePassword(ChangePasswordRequest requestParam);
}
