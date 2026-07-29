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

package com.nageoffer.ai.ragent.knowledge.service;

/**
 * outbox 清理任务入队服务。
 * 必须在删除主事务内调用，保证「业务删除」与「清理意图」原子落库。
 */
public interface CleanupTaskService {

    /**
     * 入队向量清理任务。
     */
    void enqueueVectorCleanup(String docId, String collectionName);

    /**
     * 入队文件清理任务；fileUrl 为空白时跳过。
     */
    void enqueueFileCleanup(String fileUrl);
}
