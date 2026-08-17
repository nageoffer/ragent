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

package com.nageoffer.ai.ragent.core.ingest;

/**
 * 文档身份：纯数据，字节从上传、URL 还是飞书来是内核之前的事，内核不认识取数方式
 * <p>
 * docId 还会传给解析器给图片资产命名 {@code assets/{docId}/...}，漏传则资产落进随机目录、与文档失联
 *
 * @param docId    文档 ID，决定资产归属与落库归属
 * @param kbId     所属知识库 ID，决定关系库归属
 * @param filename 原始文件名，供类型识别与溯源，可为空（删除路径不需要）
 */
public record DocumentRef(String docId, String kbId, String filename) {

    public DocumentRef {
        if (docId == null || docId.isBlank()) {
            throw new IllegalArgumentException("docId 不能为空");
        }
        if (kbId == null || kbId.isBlank()) {
            throw new IllegalArgumentException("kbId 不能为空，docId=" + docId);
        }
    }

    /**
     * 删除路径用：不需要文件名
     */
    public static DocumentRef of(String docId, String kbId) {
        return new DocumentRef(docId, kbId, null);
    }
}
