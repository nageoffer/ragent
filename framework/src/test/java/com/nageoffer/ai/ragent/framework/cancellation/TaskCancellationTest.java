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

package com.nageoffer.ai.ragent.framework.cancellation;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;

import static org.assertj.core.api.Assertions.assertThat;

class TaskCancellationTest {

    @AfterEach
    void clearInterruptFlag() {
        Thread.interrupted();
    }

    @Test
    void shouldReadInterruptFlagAsCancellation() {
        assertThat(TaskCancellation.isCancelled()).isFalse();

        Thread.currentThread().interrupt();

        assertThat(TaskCancellation.isCancelled()).isTrue();
        assertThat(TaskCancellation.isCancellation(new IOException("io"))).isTrue();
    }

    @Test
    void shouldRecognizeCancellationBuriedInCauseChain() {
        assertThat(TaskCancellation.isCancellation(new IOException("io"))).isFalse();
        assertThat(TaskCancellation.isCancellation(
                new CompletionException(new CancellationException("cancelled")))).isTrue();
        assertThat(TaskCancellation.isCancellation(
                new IllegalStateException(new InterruptedException("interrupted")))).isTrue();
    }

    @Test
    void shouldRestoreInterruptFlagWhenRebuildingCancellation() {
        CompletionException wrapped = new CompletionException(new InterruptedException("interrupted"));

        CancellationException cancellation = TaskCancellation.asCancellation(wrapped);

        assertThat(cancellation).hasCause(wrapped);
        // InterruptedException 抛出时会清掉中断标记，不补回来上游的出口判定就全失灵
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
    }

    @Test
    void shouldReuseExistingCancellationException() {
        CancellationException origin = new CancellationException("cancelled");

        assertThat(TaskCancellation.asCancellation(origin)).isSameAs(origin);
    }
}
