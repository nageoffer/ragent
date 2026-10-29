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

package com.nageoffer.ai.ragent.rag.core.security;

import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.rag.core.mcp.McpToolExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Set;

/**
 * MCP 宸ュ叿鏉冮檺鎵ц鍣?
 * <p>
 * 鍦?{@code RetrievalEngine.executeSingleMcpTool()} 涓皟鐢紝
 * 妫€鏌ュ綋鍓嶇敤鎴锋槸鍚﹀叿澶囪皟鐢ㄧ洰鏍囧伐鍏风殑鏉冮檺銆?
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class McpPermissionEnforcer {

    private final SecurityProperties properties;

    private static final String ADMIN_ROLE = "admin";

    /**
     * 妫€鏌ュ綋鍓嶇敤鎴锋槸鍚︽湁鏉冮檺璋冪敤鎸囧畾宸ュ叿
     *
     * @param toolId   宸ュ叿 ID
     * @param executor 宸ュ叿鎵ц鍣?
     * @throws ClientException 鏉冮檺涓嶈冻鏃舵姏鍑?
     */
    public void checkPermission(String toolId, McpToolExecutor executor) {
        if (!properties.getMcp().isEnforceRoles()) {
            return;
        }

        McpToolPermission perm = executor.getClass().getAnnotation(McpToolPermission.class);
        if (perm == null) {
            // 鏈爣璁?= 鏃犵壒娈婃潈闄愯姹?
            return;
        }

        String currentRole = UserContext.getRole();
        if (currentRole == null) {
            log.warn("MCP 鏉冮檺鎷掔粷: toolId={}, 褰撳墠鐢ㄦ埛鏃犺鑹?, toolId);
            throw new ClientException("褰撳墠鐢ㄦ埛鏃犺鑹叉潈闄愶紝鏃犳硶璋冪敤宸ュ叿: " + toolId);
        }

        // 绠＄悊鍛樹紭鍏?
        if (perm.adminOnly() && !ADMIN_ROLE.equalsIgnoreCase(currentRole)) {
            log.warn("MCP 鏉冮檺鎷掔粷: toolId={}, 闇€瑕佺鐞嗗憳瑙掕壊, 褰撳墠瑙掕壊={}", toolId, currentRole);
            throw new ClientException("宸ュ叿 " + toolId + " 浠呴檺绠＄悊鍛樹娇鐢?);
        }

        // 鎸囧畾瑙掕壊鍖归厤
        String[] requiredRoles = perm.requiredRoles();
        if (requiredRoles.length > 0) {
            Set<String> userRoles = Set.of(currentRole.toLowerCase().split(","));
            boolean matched = Arrays.stream(requiredRoles)
                    .anyMatch(r -> userRoles.contains(r.toLowerCase()));
            if (!matched) {
                log.warn("MCP 鏉冮檺鎷掔粷: toolId={}, 闇€瑕佽鑹?{}, 褰撳墠瑙掕壊={}", toolId, Arrays.toString(requiredRoles), currentRole);
                throw new ClientException("宸ュ叿 " + toolId + " 闇€瑕佽鑹? " + String.join(", ", requiredRoles));
            }
        }
    }
}

