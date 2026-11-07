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

package com.nageoffer.ai.ragent.agent.service.handler;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.ConfirmResult;
import java.util.List;

/** 业务运行上下文的生命周期。中断和断连回调只设置本地标记，持久化在收尾执行。 */
public interface AgentRunLifecycle {
    void onStart(RuntimeContext context, List<ConfirmResult> confirmations);
    void onInterrupt(RuntimeContext context);
    void onDisconnect(RuntimeContext context);
    void onFinish(RuntimeContext context);
}
