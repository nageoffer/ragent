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

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.util.concurrent.CancellationException;

/**
 * 判定一次失败是不是用户取消，只服务于不该把取消记成故障的出口
 * 依据是中断标记与异常链：用户停止后运行句柄 dispose 上游，Reactor 随之中断执行线程
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class TaskCancellation {

    /**
     * 下游降级返回而非抛出时，出口只剩中断标记可判
     */
    public static boolean isCancelled() {
        return Thread.currentThread().isInterrupted();
    }

    public static boolean isCancellation(Throwable error) {
        if (isCancelled()) {
            return true;
        }
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof InterruptedException || current instanceof CancellationException) {
                return true;
            }
        }
        return false;
    }

    /**
     * 还原成取消异常，并补回被 InterruptedException 清掉的中断标记，否则上游判据失灵
     */
    public static CancellationException asCancellation(Throwable cause) {
        for (Throwable current = cause; current != null; current = current.getCause()) {
            if (current instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (cause instanceof CancellationException cancellation) {
            return cancellation;
        }
        CancellationException cancellation = new CancellationException("Task cancelled");
        if (cause != null) {
            cancellation.initCause(cause);
        }
        return cancellation;
    }
}
