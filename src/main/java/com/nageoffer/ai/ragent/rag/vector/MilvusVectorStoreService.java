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

package com.nageoffer.ai.ragent.rag.vector;

import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.IdUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.nageoffer.ai.ragent.dao.entity.KnowledgeBaseDO;
import com.nageoffer.ai.ragent.dao.mapper.KnowledgeBaseMapper;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.rag.chunk.Chunk;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.request.InsertReq;
import io.milvus.v2.service.vector.request.UpsertReq;
import io.milvus.v2.service.vector.response.DeleteResp;
import io.milvus.v2.service.vector.response.InsertResp;
import io.milvus.v2.service.vector.response.UpsertResp;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class MilvusVectorStoreService implements VectorStoreService {

    private final MilvusClientV2 milvusClient;
    private final KnowledgeBaseMapper kbMapper;

    @Override
    public void indexDocumentChunks(String kbId, String docId, List<Chunk> chunks, float[][] vectors) {
        Assert.isFalse(chunks == null || chunks.isEmpty(), () -> new ClientException("文档分块不允许为空"));
        Assert.isFalse(vectors == null || vectors.length == 0, () -> new ClientException("向量不允许为空"));

        KnowledgeBaseDO kbDO = kbMapper.selectById(kbId);
        Assert.isFalse(kbDO == null, () -> new ClientException("知识库不存在"));

        // 维度校验（你的 schema dim=4096）
        final int dim = 4096;
        for (int i = 0; i < vectors.length; i++) {
            if (vectors[i] == null || vectors[i].length != dim) {
                throw new ClientException("向量维度不匹配，第 " + i + " 行，期望维度为 " + dim);
            }
        }

        List<JsonObject> rows = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            Chunk chunk = chunks.get(i);

            String content = chunk.getContent() == null ? "" : chunk.getContent();
            if (content.length() > 65535) {
                content = content.substring(0, 65535);
            }

            JsonObject metadata = new JsonObject();
            metadata.addProperty("kb_id", kbId);
            metadata.addProperty("doc_id", docId);
            metadata.addProperty("chunk_index", chunk.getIndex());

            JsonObject row = new JsonObject();
            row.addProperty("doc_id", chunk.getChunkId());
            row.addProperty("content", content);
            row.add("metadata", metadata);
            row.add("embedding", toJsonArray(vectors[i]));

            rows.add(row);
        }

        String collection = kbDO.getCollectionName();
        InsertReq req = InsertReq.builder()
                .collectionName(collection)
                .data(rows)
                .build();

        InsertResp resp = milvusClient.insert(req);
        log.info("Milvus chunk 建立/写入向量索引成功, collection={}, rows={}", collection, resp.getInsertCnt());
    }

    @Override
    public void updateChunk(String kbId, String docId, Chunk chunk, float[] vector) {
        Assert.isFalse(chunk == null, () -> new ClientException("Chunk 对象不能为空"));
        Assert.isFalse(vector == null || vector.length == 0, () -> new ClientException("向量不能为空"));

        KnowledgeBaseDO kbDO = kbMapper.selectById(kbId);
        Assert.isFalse(kbDO == null, () -> new ClientException("知识库不存在"));

        // 维度校验
        final int dim = 4096;
        if (vector.length != dim) {
            throw new ClientException("向量维度不匹配，期望维度为 " + dim);
        }

        String chunkPk = chunk.getChunkId() != null ? chunk.getChunkId() : IdUtil.getSnowflakeNextIdStr();

        String content = chunk.getContent() == null ? "" : chunk.getContent();
        if (content.length() > 65535) {
            content = content.substring(0, 65535);
        }

        JsonObject metadata = new JsonObject();
        metadata.addProperty("kb_id", kbId);
        metadata.addProperty("doc_id", docId);
        metadata.addProperty("chunk_index", chunk.getIndex());

        JsonObject row = new JsonObject();
        row.addProperty("doc_id", chunkPk);
        row.addProperty("content", content);
        row.add("metadata", metadata);
        row.add("embedding", toJsonArray(vector));

        List<JsonObject> rows = List.of(row);

        String collection = kbDO.getCollectionName();

        UpsertReq upsertReq = UpsertReq.builder()
                .collectionName(collection)
                .data(rows)
                .build();

        UpsertResp resp = milvusClient.upsert(upsertReq);

        log.info("Milvus 更新 chunk 向量索引成功, collection={}, kbId={}, docId={}, chunkId={}, upsertCnt={}",
                collection, kbId, docId, chunkPk, resp.getUpsertCnt());
    }

    @Override
    public void deleteDocumentVectors(String kbId, String docId) {
        KnowledgeBaseDO kbDO = kbMapper.selectById(kbId);
        Assert.isNull(kbDO, () -> new ClientException("知识库不存在"));

        String collection = kbDO.getCollectionName();

        // 按 JSON 过滤：删除该 kbId 下、该文档ID 的所有 chunk
        String filter = "metadata[\"kb_id\"] == \"" + kbId + "\" && " +
                "metadata[\"doc_id\"] == \"" + docId + "\"";

        DeleteReq deleteReq = DeleteReq.builder()
                .collectionName(collection)
                .filter(filter)
                .build();

        DeleteResp resp = milvusClient.delete(deleteReq);
        log.info("Milvus 删除指定文档的所有 chunk 向量索引成功, collection={}, kbId={}, docId={}, deleteCnt={}",
                collection, kbId, docId, resp.getDeleteCnt());
    }


    @Override
    public void deleteChunkById(String kbId, String chunkId) {
        KnowledgeBaseDO kbDO = kbMapper.selectById(kbId);
        Assert.isFalse(kbDO == null, () -> new ClientException("知识库不存在"));

        String collection = kbDO.getCollectionName();

        // chunkId 就是 Milvus 中的 doc_id（主键），直接通过主键删除
        String filter = "doc_id == \"" + chunkId + "\"";

        DeleteReq deleteReq = DeleteReq.builder()
                .collectionName(collection)
                .filter(filter)
                .build();

        DeleteResp resp = milvusClient.delete(deleteReq);
        log.info("Milvus 删除指定 chunk 向量索引成功, collection={}, kbId={}, chunkId={}, deleteCnt={}",
                collection, kbId, chunkId, resp.getDeleteCnt());
    }

    private JsonArray toJsonArray(float[] v) {
        JsonArray arr = new JsonArray(v.length);
        for (float x : v) {
            arr.add(x);
        }
        return arr;
    }
}

