package com.nageoffer.ai.ragent.service.rag.vector;

import cn.hutool.core.util.IdUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.nageoffer.ai.ragent.dao.entity.KnowledgeBaseDO;
import com.nageoffer.ai.ragent.dao.mapper.KnowledgeBaseMapper;
import com.nageoffer.ai.ragent.service.rag.chunk.Chunk;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.request.InsertReq;
import io.milvus.v2.service.vector.response.DeleteResp;
import io.milvus.v2.service.vector.response.InsertResp;
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
    public void upsert(String kbId, String docId, List<Chunk> chunks, float[][] vectors) {
        if (chunks == null || chunks.isEmpty()) return;
        if (vectors == null || vectors.length != chunks.size()) {
            throw new IllegalArgumentException("vectors size != chunks size");
        }

        KnowledgeBaseDO kb = kbMapper.selectById(kbId);
        if (kb == null) throw new IllegalArgumentException("知识库不存在: " + kbId);
        final String collection = kb.getCollectionName();

        // 维度校验（你的 schema dim=4096）
        final int dim = 4096;
        for (int i = 0; i < vectors.length; i++) {
            if (vectors[i] == null || vectors[i].length != dim) {
                throw new IllegalArgumentException("Embedding dim mismatch at row " + i + ", expect " + dim);
            }
        }

        List<JsonObject> rows = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            Chunk c = chunks.get(i);

            String chunkPk = IdUtil.getSnowflakeNextIdStr();

            String content = c.getContent() == null ? "" : c.getContent();
            if (content.length() > 65535) {
                content = content.substring(0, 65535);
            }

            JsonObject meta = new JsonObject();
            meta.addProperty("kb_id", kbId);
            meta.addProperty("doc_id", docId);
            meta.addProperty("chunk_index", c.getIndex());

            JsonObject row = new JsonObject();
            row.addProperty("doc_id", chunkPk);
            row.addProperty("content", content);
            row.add("metadata", meta);
            row.add("embedding", toJsonArray(vectors[i]));

            rows.add(row);
        }

        InsertReq req = InsertReq.builder()
                .collectionName(collection)
                .data(rows)
                .build();

        InsertResp resp = milvusClient.insert(req);
        log.info("Milvus upsert ok, collection={}, rows={}", collection, resp.getInsertCnt());
    }

    @Override
    public void removeByDocId(String kbId, String docId) {
        KnowledgeBaseDO kb = kbMapper.selectById(kbId);
        if (kb == null) throw new IllegalArgumentException("知识库不存在: " + kbId);
        final String collection = kb.getCollectionName();

        // 按 JSON 过滤：删除该 kbId 下、该文档ID 的所有 chunk
        String filter = "metadata[\"kb_id\"] == \"" + escape(kbId) + "\" && " +
                "metadata[\"doc_id\"] == \"" + escape(docId) + "\"";

        DeleteReq deleteReq = DeleteReq.builder()
                .collectionName(collection)
                .filter(filter)
                .build();

        DeleteResp resp = milvusClient.delete(deleteReq);
        log.info("Milvus delete by doc_id ok, collection={}, kbId={}, docId={}, deleteCnt={}",
                collection, kbId, docId, resp.getDeleteCnt());
    }

    @Override
    public void markEnabled(String kbId, String docId, boolean enabled) {
        // 可在检索时用 filter 排除禁用文档；或维护一个禁用清单（内存/Redis），这里先打日志
        log.info("Doc enable state marked. kbId={}, docId={}, enabled={}", kbId, docId, enabled);
    }

    // ===== 工具 =====
    private static JsonArray toJsonArray(float[] v) {
        JsonArray arr = new JsonArray(v.length);
        for (float x : v) arr.add(x);  // Gson 会写成 JSON number
        return arr;
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}

