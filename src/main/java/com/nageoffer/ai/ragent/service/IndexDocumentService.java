package com.nageoffer.ai.ragent.service;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.nageoffer.ai.ragent.config.RAGDefaultProperties;
import com.nageoffer.ai.ragent.dto.DocumentChunk;
import com.nageoffer.ai.ragent.dto.DocumentIndexResult;
import com.nageoffer.ai.ragent.rag.embedding.EmbeddingService;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.request.InsertReq;
import io.milvus.v2.service.vector.request.QueryReq;
import io.milvus.v2.service.vector.response.DeleteResp;
import io.milvus.v2.service.vector.response.InsertResp;
import io.milvus.v2.service.vector.response.QueryResp;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class IndexDocumentService {

    private static final int DEFAULT_CHUNK_SIZE = 800;
    private static final int DEFAULT_CHUNK_OVERLAP = 200;
    private static final int MAX_CHUNKS_PER_DOC_QUERY = 2000;

    private final MilvusClientV2 milvusClient;
    private final EmbeddingService embeddingService;
    private final RAGDefaultProperties ragDefaultProperties;

    private final Tika tika = new Tika();
    private final Gson gson = new Gson();

    /**
     * 文件入库：支持 PDF / Markdown / Doc / Docx
     */
    public DocumentIndexResult indexFile(MultipartFile file, String documentId) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("上传文件不能为空");
        }

        String filename = file.getOriginalFilename();
        String contentType = file.getContentType();
        String sourceType = detectSourceType(filename, contentType);

        String docId = StringUtils.hasText(documentId)
                ? documentId
                : UUID.randomUUID().toString();

        String text = extractText(file);

        List<String> chunks = splitIntoChunks(text, DEFAULT_CHUNK_SIZE, DEFAULT_CHUNK_OVERLAP);

        List<JsonObject> rows = buildRowsForChunks(
                docId,
                filename,
                sourceType,
                contentType,
                chunks
        );

        InsertReq req = InsertReq.builder()
                .collectionName(ragDefaultProperties.getCollectionName())
                .data(rows)
                .build();

        InsertResp resp = milvusClient.insert(req);
        log.info("Indexed file document. documentId={}, file={}, sourceType={}, chunks={}, insertCnt={}",
                docId, filename, sourceType, chunks.size(), resp.getInsertCnt());

        return new DocumentIndexResult(docId, filename, sourceType, chunks.size());
    }

    /**
     * 删除整个文档（所有 chunk）
     */
    public long deleteDocument(String documentId) {
        if (!StringUtils.hasText(documentId)) {
            throw new IllegalArgumentException("documentId 不能为空");
        }

        String filter = String.format("metadata[\"documentId\"] == \"%s\"", documentId);

        DeleteReq deleteReq = DeleteReq.builder()
                .collectionName(ragDefaultProperties.getCollectionName())
                .filter(filter)
                .build();

        DeleteResp resp = milvusClient.delete(deleteReq);
        long deleted = resp.getDeleteCnt();
        log.info("Deleted document. documentId={}, deletedChunks={}", documentId, deleted);
        return deleted;
    }

    /**
     * 更新文档 = 先删再重新索引
     */
    public DocumentIndexResult reindexDocument(String documentId, MultipartFile file) {
        deleteDocument(documentId);
        return indexFile(file, documentId);
    }

    /**
     * 查询某个文档的所有 chunk（主要给后台管理查看用）
     */
    public List<DocumentChunk> getDocumentChunks(String documentId) {
        if (!StringUtils.hasText(documentId)) {
            throw new IllegalArgumentException("documentId 不能为空");
        }

        String filter = String.format("metadata[\"documentId\"] == \"%s\"", documentId);

        QueryReq queryReq = QueryReq.builder()
                .collectionName(ragDefaultProperties.getCollectionName())
                .filter(filter)
                .outputFields(List.of("doc_id", "content", "metadata"))
                .limit(MAX_CHUNKS_PER_DOC_QUERY)
                .build();

        QueryResp resp = milvusClient.query(queryReq);
        List<QueryResp.QueryResult> results = resp.getQueryResults();
        if (results == null || results.isEmpty()) {
            return List.of();
        }

        return results.stream()
                .map(qr -> {
                    Map<String, Object> entity = qr.getEntity();
                    String chunkId = Objects.toString(entity.get("doc_id"), null);
                    String content = Objects.toString(entity.get("content"), "");
                    // metadata 可能是 Map / 字符串，都统一转成 JsonObject
                    JsonObject metadataJson = gson.fromJson(
                            gson.toJson(entity.get("metadata")),
                            JsonObject.class
                    );
                    Integer chunkIndex = metadataJson.has("chunkIndex")
                            ? metadataJson.get("chunkIndex").getAsInt()
                            : null;
                    return new DocumentChunk(
                            chunkId,
                            documentId,
                            content,
                            chunkIndex,
                            metadataJson.toString()
                    );
                })
                .sorted(Comparator.comparing(
                        c -> Optional.ofNullable(c.getChunkIndex()).orElse(0)))
                .collect(Collectors.toList());
    }

    // ========== 内部工具方法 ==========

    private String buildTextWithTitle(String title, String content) {
        if (StringUtils.hasText(title)) {
            return title + "\n\n" + content;
        }
        return content;
    }

    /**
     * 使用 Apache Tika 从多种文档中提取文本
     */
    private String extractText(MultipartFile file) {
        try (InputStream is = file.getInputStream()) {
            String text = tika.parseToString(is);
            return text != null ? text.trim() : "";
        } catch (IOException | TikaException e) {
            throw new RuntimeException("解析文件失败: " + file.getOriginalFilename(), e);
        }
    }

    /**
     * 简单的按长度切 chunk，带 overlap
     */
    private List<String> splitIntoChunks(String text, int chunkSize, int overlap) {
        if (!StringUtils.hasText(text)) {
            return List.of();
        }
        List<String> chunks = new ArrayList<>();
        int len = text.length();
        int start = 0;
        while (start < len) {
            int end = Math.min(start + chunkSize, len);
            String chunk = text.substring(start, end).trim();
            if (StringUtils.hasText(chunk)) {
                chunks.add(chunk);
            }
            if (end == len) break;
            start = end - overlap;
            if (start < 0) start = 0;
        }
        return chunks;
    }

    /**
     * 构造一批向量插入行
     */
    private List<JsonObject> buildRowsForChunks(String documentId,
                                                String name,
                                                String sourceType,
                                                String contentType,
                                                List<String> chunks) {
        List<JsonObject> rows = new ArrayList<>();
        long now = System.currentTimeMillis();

        for (int i = 0; i < chunks.size(); i++) {
            String chunk = chunks.get(i);
            if (!StringUtils.hasText(chunk)) continue;

            List<Float> emb = embeddingService.embed(chunk);

            JsonObject row = new JsonObject();
            // 每个 chunk 一个独立主键
            row.addProperty("doc_id", UUID.randomUUID().toString());
            row.add("embedding", floatListToJson(emb));
            row.addProperty("content", chunk);

            JsonObject metadata = new JsonObject();
            metadata.addProperty("documentId", documentId);
            if (StringUtils.hasText(name)) {
                metadata.addProperty("name", name);
            }
            if (StringUtils.hasText(sourceType)) {
                metadata.addProperty("sourceType", sourceType);
            }
            if (StringUtils.hasText(contentType)) {
                metadata.addProperty("contentType", contentType);
            }
            metadata.addProperty("chunkIndex", i);
            metadata.addProperty("totalChunks", chunks.size());
            metadata.addProperty("timestamp", now);
            row.add("metadata", metadata);

            rows.add(row);
        }
        return rows;
    }

    private JsonArray floatListToJson(List<Float> list) {
        JsonArray arr = new JsonArray();
        list.forEach(arr::add);
        return arr;
    }

    /**
     * 根据文件名 / ContentType 粗略判断来源类型
     */
    private String detectSourceType(String filename, String contentType) {
        String lowerName = filename != null ? filename.toLowerCase() : "";
        if (lowerName.endsWith(".pdf")) return "pdf";
        if (lowerName.endsWith(".md") || lowerName.endsWith(".markdown")) return "markdown";
        if (lowerName.endsWith(".doc") || lowerName.endsWith(".docx")) return "doc";
        if (contentType != null && contentType.contains("pdf")) return "pdf";
        if (contentType != null && contentType.contains("markdown")) return "markdown";
        if (contentType != null && contentType.contains("msword")) return "doc";
        return "unknown";
    }
}
