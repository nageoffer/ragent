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

package com.nageoffer.ai.ragent.framework.idempotent;

import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.mock.web.MockMultipartFile;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IdempotentSubmitAspectTest {

    @Test
    void shouldGenerateDigestFromMultipartMetadataWithoutReadingContent() throws Exception {
        byte[] firstContent = new byte[20 * 1024 * 1024];
        byte[] differentContent = firstContent.clone();
        differentContent[differentContent.length - 1] = 1;
        MockMultipartFile first = new MockMultipartFile("file", "first.pdf", "application/pdf", firstContent);
        MockMultipartFile sameMetadata =
                new MockMultipartFile("file", "first.pdf", "application/pdf", differentContent);
        MockMultipartFile differentName =
                new MockMultipartFile("file", "second.pdf", "application/pdf", firstContent);

        assertEquals(calcArgsMd5(first), calcArgsMd5(sameMetadata));
        assertNotEquals(calcArgsMd5(first), calcArgsMd5(differentName));
    }

    private String calcArgsMd5(MockMultipartFile file) throws Exception {
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        when(joinPoint.getArgs()).thenReturn(new Object[]{file});
        IdempotentSubmitAspect aspect = new IdempotentSubmitAspect(mock(RedissonClient.class));
        Method method = IdempotentSubmitAspect.class.getDeclaredMethod("calcArgsMD5", ProceedingJoinPoint.class);
        method.setAccessible(true);
        return (String) method.invoke(aspect, joinPoint);
    }
}
