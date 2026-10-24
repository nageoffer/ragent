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

package com.nageoffer.ai.ragent.agent.tool;

import io.agentscope.core.tool.AgentTool;
import java.util.List;
import java.util.Set;

/** 业务提供工具适配与附加工具，目录只负责发现和注册。 */
public interface AgentToolContributor {
    Set<String> reservedToolNames();

    /** 不接管该工具时返回 null，继续使用通用 MCP 代理。 */
    AgentTool createTool(AgentToolCatalog.McpToolBinding binding);

    List<AgentTool> additionalTools(List<AgentToolCatalog.McpToolBinding> bindings);
}
