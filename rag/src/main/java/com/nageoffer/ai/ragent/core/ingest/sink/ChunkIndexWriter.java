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

package com.nageoffer.ai.ragent.core.ingest.sink;

import com.nageoffer.ai.ragent.core.chunk.model.EmbeddedChunk;
import com.nageoffer.ai.ragent.core.ingest.DocumentRef;
import com.nageoffer.ai.ragent.core.ingest.VectorTarget;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;

import java.util.List;

/**
 * 索引扇出：把块整体写进全部落点，事务边界在此
 * <p>
 * 加一个索引后端 = 加一个 {@link ChunkSink} bean，本类与内核都一行不改
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChunkIndexWriter {

    private final List<ChunkSink> sinks;
    private final TransactionOperations transactionOperations;

    /**
     * 整体替换该文档的块：全部落点在同一个事务里
     */
    public void replaceDocument(VectorTarget target, DocumentRef doc, List<EmbeddedChunk> chunks) {
        transactionOperations.executeWithoutResult(status ->
                sinks.forEach(sink -> sink.replaceDocument(target, doc, chunks)));
        log.info("块索引写入完成 docId={} 分区={} 块数={} 落点数={}",
                doc.docId(), target.partition(), chunks.size(), sinks.size());
    }

    public void deleteDocument(VectorTarget target, DocumentRef doc) {
        transactionOperations.executeWithoutResult(status ->
                sinks.forEach(sink -> sink.deleteDocument(target, doc)));
    }
}
