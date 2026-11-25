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

package com.nageoffer.ai.ragent.infra.http;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class UpstreamErrorTest {

    private static final String ECHOED = "用户原文-左耳没声音";

    @Test
    void shouldKeepCodeFieldsAndDropMessageOfOpenAiStyleBody() {
        UpstreamError error = UpstreamError.parse(400,
                "{\"error\":{\"code\":\"invalid_parameter_error\",\"type\":\"invalid_request_error\",\"param\":\"max_tokens\","
                        + "\"message\":\"" + ECHOED + "\"},\"request_id\":\"req-1\"}",
                null);

        assertThat(error.toString())
                .isEqualTo("HTTP 400 code=invalid_parameter_error type=invalid_request_error param=max_tokens request_id=req-1")
                .doesNotContain(ECHOED);
        assertThat(error.errorType()).isEqualTo(ModelClientErrorType.CLIENT_ERROR);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"error\":{\"code\":\"data_inspection_failed\",\"type\":\"data_inspection_failed\",\"param\":null,\"message\":\"x\"}}",
            "{\"code\":\"DataInspectionFailed\",\"message\":\"x\",\"request_id\":\"req-2\"}"
    })
    void shouldRecognizeBothContentRejectionSpellings(String body) {
        assertThat(UpstreamError.parse(400, body, null).errorType()).isEqualTo(ModelClientErrorType.CONTENT_REJECTED);
    }

    @Test
    void shouldFallBackToStatusAndHeaderWhenBodyIsNotJson() {
        UpstreamError error = UpstreamError.parse(502, "<html>" + ECHOED + "</html>", "hdr-9");

        assertThat(error.toString()).isEqualTo("HTTP 502 code=- type=- param=- request_id=hdr-9");
        assertThat(error.errorType()).isEqualTo(ModelClientErrorType.SERVER_ERROR);
    }
}
