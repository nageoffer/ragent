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

package com.nageoffer.ai.ragent.core.parser.mime;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.apache.tika.Tika;

/**
 * MIME 探测器：字节语义的唯一权威源，产出只服务解析路由，不参与展示
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class MimeTypeDetector {

    private static final Tika TIKA = new Tika();

    /**
     * 按字节 + 文件名探测 MIME，文件名可为空，字节为空返回 null
     */
    public static String detect(byte[] bytes, String fileName) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        if (fileName == null) {
            return TIKA.detect(bytes);
        }
        return TIKA.detect(bytes, fileName);
    }
}
