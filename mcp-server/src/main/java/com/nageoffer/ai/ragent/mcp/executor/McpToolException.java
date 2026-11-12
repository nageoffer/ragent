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

package com.nageoffer.ai.ragent.mcp.executor;

/**
 * 消息可以原样返回给模型的工具异常
 * <p>
 * 一条异常消息能不能给模型看，只有抛出它的那行代码知道：自己拼的「上游返回 503」是安全的，
 * 底层库抛的可能带着 SQL 片段或连接串。把这件事做成类型，抛出方声明一次，
 * {@link McpToolResults#failure} 照着办，捕获方不必在 catch 里猜
 */
public class McpToolException extends RuntimeException {

    public McpToolException(String message) {
        super(message);
    }
}
