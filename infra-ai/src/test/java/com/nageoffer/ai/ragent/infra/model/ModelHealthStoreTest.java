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

package com.nageoffer.ai.ragent.infra.model;

import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ModelHealthStoreTest {

    private static final String MODEL_ID = "model";

    private ModelHealthStore healthStore;

    @BeforeEach
    void setUp() {
        AIModelProperties properties = new AIModelProperties();
        properties.getSelection().setFailureThreshold(1);
        properties.getSelection().setOpenDurationMs(0L);
        healthStore = new ModelHealthStore(properties);
    }

    @Test
    void staleClosedSuccessCannotCloseCurrentHalfOpenProbe() {
        ModelHealthStore.CallPermit stale = healthStore.allowCall(MODEL_ID);
        ModelHealthStore.CallPermit opener = healthStore.allowCall(MODEL_ID);
        healthStore.markFailure(opener);

        ModelHealthStore.CallPermit probe = healthStore.allowCall(MODEL_ID);
        assertThat(probe).isNotNull();
        healthStore.markSuccess(stale);

        assertThat(probe.halfOpenToken()).isPositive();
        assertThat(healthStore.isUnavailable(MODEL_ID)).isTrue();
        assertThat(healthStore.allowCall(MODEL_ID)).isNull();
    }

    @Test
    void staleClosedFailureCannotReopenCircuitAfterProbeSucceeds() {
        ModelHealthStore.CallPermit stale = healthStore.allowCall(MODEL_ID);
        ModelHealthStore.CallPermit opener = healthStore.allowCall(MODEL_ID);
        healthStore.markFailure(opener);

        ModelHealthStore.CallPermit probe = healthStore.allowCall(MODEL_ID);
        assertThat(probe).isNotNull();
        healthStore.markSuccess(probe);
        healthStore.markFailure(stale);

        assertThat(healthStore.isUnavailable(MODEL_ID)).isFalse();
        assertThat(healthStore.allowCall(MODEL_ID)).isNotNull();
    }

    @Test
    void completedProbeCannotOverwriteLaterGeneration() {
        ModelHealthStore.CallPermit first = healthStore.allowCall(MODEL_ID);
        healthStore.markFailure(first);
        ModelHealthStore.CallPermit probe = healthStore.allowCall(MODEL_ID);
        assertThat(probe).isNotNull();
        healthStore.markSuccess(probe);

        healthStore.markFailure(probe);

        assertThat(healthStore.isUnavailable(MODEL_ID)).isFalse();
        assertThat(healthStore.allowCall(MODEL_ID)).isNotNull();
    }
}
