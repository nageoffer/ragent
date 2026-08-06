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

/** 验证成功回调在全部索引落点写完后、写入事务结束前执行。 */
class ChunkIndexWriterTest {

    @Test
    void invokesCompletionAfterEverySinkWithinTransaction() {
        ChunkSink first = mock(ChunkSink.class);
        ChunkSink second = mock(ChunkSink.class);
        Runnable beforeCommit = mock(Runnable.class);
        TransactionOperations transactions = immediateTransactions();
        ChunkIndexWriter writer = new ChunkIndexWriter(List.of(first, second), transactions);
        DocumentRef doc = new DocumentRef("doc-1", "kb-1", "guide.md");
        VectorTarget target = new VectorTarget("kb", "embedding", 3);

        writer.replaceDocument(target, doc, List.of(), beforeCommit);

        InOrder order = inOrder(first, second, beforeCommit);
        order.verify(first).replaceDocument(target, doc, List.of());
        order.verify(second).replaceDocument(target, doc, List.of());
        order.verify(beforeCommit).run();
    }

    @Test
    void skipsCompletionWhenAnySinkFails() {
        ChunkSink sink = mock(ChunkSink.class);
        Runnable beforeCommit = mock(Runnable.class);
        TransactionOperations transactions = immediateTransactions();
        ChunkIndexWriter writer = new ChunkIndexWriter(List.of(sink), transactions);
        DocumentRef doc = new DocumentRef("doc-1", "kb-1", "guide.md");
        VectorTarget target = new VectorTarget("kb", "embedding", 3);
        doThrow(new IllegalStateException("write failed"))
                .when(sink).replaceDocument(target, doc, List.of());

        assertThatThrownBy(() -> writer.replaceDocument(target, doc, List.of(), beforeCommit))
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
