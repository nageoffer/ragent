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

package com.nageoffer.ai.ragent.ingestion.domain.result;

import com.nageoffer.ai.ragent.ingestion.domain.enums.IngestionStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 摄取结果实体类
 * 表示文档摄取任务执行完成后的结果信息，包含任务状态、分块数量等概要数据
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IngestionResult {

    /**
     * 摄取任务的唯一标识符
     */
    private String taskId;

    /**
     * 执行本次摄取的管道ID
     */
    private String pipelineId;

    /**
     * 摄取任务的最终状态
     */
    private IngestionStatus status;

    /**
     * 文档被切分成的块数量
     */
    private Integer chunkCount;

    /**
     * 执行结果的消息说明
     * 成功时为概要信息，失败时为错误原因
     */
    private String message;
}
