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

package com.nageoffer.ai.ragent.knowledge.service.impl;

import com.nageoffer.ai.ragent.knowledge.dao.entity.FileCleanupTaskDO;
import com.nageoffer.ai.ragent.knowledge.dao.entity.VectorCleanupTaskDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.FileCleanupTaskMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.VectorCleanupTaskMapper;
import com.nageoffer.ai.ragent.knowledge.enums.CleanupTaskStatus;
import com.nageoffer.ai.ragent.knowledge.service.CleanupTaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class CleanupTaskServiceImpl implements CleanupTaskService {

    private final VectorCleanupTaskMapper vectorMapper;
    private final FileCleanupTaskMapper fileMapper;

    @Override
    public void enqueueVectorCleanup(String docId, String collectionName) {
        VectorCleanupTaskDO task = VectorCleanupTaskDO.builder()
                .docId(docId)
                .collectionName(collectionName)
                .status(CleanupTaskStatus.PENDING.getCode())
                .retryCount(0)
                .build();
        vectorMapper.insert(task);
    }

    @Override
    public void enqueueFileCleanup(String fileUrl) {
        if (!StringUtils.hasText(fileUrl)) {
            return;
        }
        FileCleanupTaskDO task = FileCleanupTaskDO.builder()
                .fileUrl(fileUrl)
                .status(CleanupTaskStatus.PENDING.getCode())
                .retryCount(0)
                .build();
        fileMapper.insert(task);
    }
}
