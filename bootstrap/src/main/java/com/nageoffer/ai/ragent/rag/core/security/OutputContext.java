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

/**
 * 杈撳嚭瀹夊叏杩囨护涓婁笅鏂?鈥斺€?璺?chunk 绱Н妫€娴?
 */
public class OutputContext {

    private final StringBuilder buffer = new StringBuilder();
    private boolean terminated = false;
    private final String taskId;

    public OutputContext(String taskId) {
        this.taskId = taskId;
    }

    public void append(String chunk) {
        buffer.append(chunk);
    }

    public String getBuffer() {
        return buffer.toString();
    }

    public int getBufferLength() {
        return buffer.length();
    }

    /**
     * 缂撳啿鍖鸿秴杩囦笂闄愭椂鎴柇鍓嶅崐閮ㄥ垎
     */
    public void truncateHalf() {
        int half = buffer.length() / 2;
        buffer.delete(0, half);
    }

    public boolean isTerminated() {
        return terminated;
    }

    public void markTerminated() {
        this.terminated = true;
    }

    public String getTaskId() {
        return taskId;
    }
}

