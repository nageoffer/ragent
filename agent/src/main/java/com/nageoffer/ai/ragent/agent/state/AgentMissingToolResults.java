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

package com.nageoffer.ai.ragent.agent.state;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.ToolResultMessageBuilder;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 给会话上下文里没有结果的工具调用补一条结果：各家协议都要求一批调用后面紧跟它们的结果，缺了这个会话之后每一问都被拒
 * 停在模型输出工具调用的半路、两次存盘之间进程退出都会留下这种状态，所以在加载时补，读历史的路径都经过这里
 */
public final class AgentMissingToolResults {

    /**
     * 补的是通用兜底，不能断定工具没跑过，写工具可能已经生效
     */
    static final String UNKNOWN_RESULT = "未收到该工具的执行结果，执行状态未知";

    private AgentMissingToolResults() {
    }

    /**
     * 逐批配对：一批调用只认紧跟在它后面的结果，别的批次复用同一个 id 也不算数
     * 批次之后没有别的消息时不碰：那是待确认的卡或框架自己会补的
     *
     * @return 补了几条结果
     */
    public static int fill(List<Msg> context) {
        int filled = 0;
        for (int i = 0; i < context.size(); i++) {
            List<ToolUseBlock> calls = context.get(i).getContentBlocks(ToolUseBlock.class);
            if (calls.isEmpty()) {
                continue;
            }
            Set<String> answered = new HashSet<>();
            int next = i + 1;
            while (next < context.size() && context.get(next).hasContentBlocks(ToolResultBlock.class)) {
                context.get(next).getContentBlocks(ToolResultBlock.class).forEach(result -> answered.add(result.getId()));
                next++;
            }
            if (next == context.size()) {
                continue;
            }
            for (ToolUseBlock call : calls) {
                if (answered.contains(call.getId())) {
                    continue;
                }
                ToolResultBlock result = new ToolResultBlock(call.getId(), call.getName(),
                        List.of(TextBlock.builder().text(UNKNOWN_RESULT).build()), null, ToolResultState.ERROR);
                context.add(next++, ToolResultMessageBuilder.buildToolResultMsg(result, call, context.get(i).getName()));
                filled++;
            }
            i = next - 1;
        }
        return filled;
    }
}
