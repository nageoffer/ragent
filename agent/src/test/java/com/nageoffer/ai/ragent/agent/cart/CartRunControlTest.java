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

package com.nageoffer.ai.ragent.agent.cart;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class CartRunControlTest {
    @Test void disconnectImmediatelyBlocksLocalWritesBeforeQueuedCancellation() {
        var store = mock(CartOperationStore.class);
        var control = new CartRunControl(store, "task", List.of());
        assertThat(control.cancelled()).isFalse();
        control.markDisconnected();
        assertThat(control.cancelled()).isTrue();
        // 模拟异步取消仍在队列中：标记阶段不执行数据库写入。
        verifyNoInteractions(store);
    }

    @Test void queuedCancellationStillPersistsDisconnectReason() {
        var store = mock(CartOperationStore.class);
        var control = new CartRunControl(store, "task", List.of());
        control.markDisconnected();
        control.cancel();
        assertThat(control.cancelled()).isTrue();
        verify(store).stopTask("task", true);
    }

    @Test void explicitStopRemainsDistinctFromDisconnect() {
        var store = mock(CartOperationStore.class);
        var control = new CartRunControl(store, "task", List.of());
        control.cancel();
        assertThat(control.cancelled()).isTrue();
        verify(store).stopTask("task", false);
    }
}
