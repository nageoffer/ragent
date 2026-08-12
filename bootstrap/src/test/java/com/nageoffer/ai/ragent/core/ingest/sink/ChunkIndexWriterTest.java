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

import com.nageoffer.ai.ragent.core.ingest.DocumentRef;
import com.nageoffer.ai.ragent.core.ingest.VectorTarget;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionOperations;

import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class ChunkIndexWriterTest {

    // 验证 Sink 顺序为 beforeWrite、关系落点、向量落点、beforeCommit
    @Test
    void invokesCallbacksAroundEverySinkWithinTransaction() {
        ChunkSink relational = mock(ChunkSink.class);
        ChunkSink vector = mock(ChunkSink.class);
        Runnable beforeWrite = mock(Runnable.class);
        Runnable beforeCommit = mock(Runnable.class);
        ChunkIndexWriter writer = new ChunkIndexWriter(
                List.of(relational, vector), immediateTransactions());
        DocumentRef doc = new DocumentRef("doc-1", "kb-1", "guide.md", "version-1");
        VectorTarget target = new VectorTarget("kb", "embedding", 3);

        writer.replaceDocument(target, doc, List.of(), beforeWrite, beforeCommit);

        InOrder order = inOrder(beforeWrite, relational, vector, beforeCommit);
        order.verify(beforeWrite).run();
        order.verify(relational).replaceDocument(target, doc, List.of());
        order.verify(vector).replaceDocument(target, doc, List.of());
        order.verify(beforeCommit).run();
    }

    // 验证 beforeWrite 或 Sink 失败时不会执行成功收尾
    @Test
    void skipsCompletionWhenWriteFails() {
        ChunkSink sink = mock(ChunkSink.class);
        Runnable beforeWrite = mock(Runnable.class);
        Runnable beforeCommit = mock(Runnable.class);
        ChunkIndexWriter writer = new ChunkIndexWriter(List.of(sink), immediateTransactions());
        DocumentRef doc = new DocumentRef("doc-1", "kb-1", "guide.md", "version-1");
        VectorTarget target = new VectorTarget("kb", "embedding", 3);
        doThrow(new IllegalStateException("write failed"))
                .when(sink).replaceDocument(target, doc, List.of());

        assertThatThrownBy(() -> writer.replaceDocument(
                target, doc, List.of(), beforeWrite, beforeCommit))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("write failed");

        verify(beforeCommit, never()).run();
    }

    private TransactionOperations immediateTransactions() {
        TransactionOperations transactions = mock(TransactionOperations.class);
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Consumer<TransactionStatus> callback = invocation.getArgument(0);
            callback.accept(mock(TransactionStatus.class));
            return null;
        }).when(transactions).executeWithoutResult(any());
        return transactions;
    }
}
