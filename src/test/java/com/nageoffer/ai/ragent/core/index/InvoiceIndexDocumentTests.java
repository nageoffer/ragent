package com.nageoffer.ai.ragent.core.index;

import cn.hutool.core.util.IdUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.nageoffer.ai.ragent.core.service.RAGService;
import com.nageoffer.ai.ragent.core.service.rag.chat.LLMService;
import com.nageoffer.ai.ragent.core.service.rag.embedding.EmbeddingService;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.vector.request.InsertReq;
import io.milvus.v2.service.vector.response.InsertResp;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@SpringBootTest
public class InvoiceIndexDocumentTests {

    @Autowired
    private LLMService llmService;

    @Autowired
    private EmbeddingService embeddingService;

    @Autowired
    private MilvusClientV2 milvusClient;

    @Autowired
    private RAGService ragService;

    @Value("${rag.collection-name}")
    private String collectionName;

    private final Tika tika = new Tika();

    @Test
    void indexDocument() throws TikaException, IOException {
        String filePath = "src/main/resources/file/公司人事/开票信息.md";
        String actualDocument = extractText(filePath);
        System.out.println(actualDocument);
        List<String> chunks = splitIntoLineChunks(actualDocument, 5);

        String docId = UUID.randomUUID().toString();
        List<JsonObject> rows = buildRowsForChunks(
                docId,
                chunks
        );

        InsertReq req = InsertReq.builder()
                .collectionName(collectionName)
                .data(rows)
                .build();

        InsertResp resp = milvusClient.insert(req);
        log.info("Indexed file document. documentId={},  chunks={}, insertCnt={}", docId, chunks.size(), resp.getInsertCnt());
    }

    @Test
    public void ragQuery() {
        String question = "阿里巴巴发票抬头";
        String answer = ragService.answer(question, 5);
        // TODO 梳理专用发票搜索 RAG 提示词
        System.out.println(answer);
    }

    private String extractText(String filePath) throws TikaException, IOException {
        Path path = Paths.get(filePath);
        Metadata metaData = new Metadata();
        String fileContent = tika.parseToString(Files.newInputStream(path), metaData);

        String prompt = """
                你是一名专业的企业文档清洗助手，负责从 Markdown 文档中提取“有用的业务字段”，并清除以下所有噪声内容：
                
                【需要删除的内容】
                - 所有 Markdown 标记：###、---、:::、```、> 等
                - 所有主题标题、装饰性标题、空白标题
                - 所有无意义分隔符（---、***）
                - 所有多余空行
                - 无意义的提示/说明性文字
                
                【需要保留的内容】
                - 真实业务信息，例如：
                  - 开票抬头
                  - 纳税人识别号
                  - 银行账号
                  - 公司地址与电话
                  - 其他结构化字段
                
                【输出要求】
                1. 仅输出清洗后的核心内容
                2. 保留原始字段结构（例如“开票抬头：xxx”）
                3. 不要添加虚构信息
                4. 不要改变字段顺序
                5. 不要解释，只输出清洗后的文本
                
                【需要处理的原文】：
                %s
                """
                .formatted(fileContent);
        return llmService.chat(prompt);
    }

    /**
     * 按行拆分
     */
    private List<String> splitIntoLineChunks(String text, int linesPerChunk) {
        if (!StringUtils.hasText(text)) {
            return List.of();
        }

        List<String> chunks = new ArrayList<>();
        List<String> lines = Arrays.asList(text.split("\n"));

        for (int i = 0; i < lines.size(); i += linesPerChunk) {
            int end = Math.min(i + linesPerChunk, lines.size());
            List<String> sub = lines.subList(i, end);

            // 检查 chunk 是否全是空行（忽略空白字符）
            boolean allEmpty = sub.stream()
                    .map(String::trim)
                    .allMatch(line -> !StringUtils.hasText(line));

            if (allEmpty) {
                continue; // 跳过纯空 chunk
            }

            // 保留原格式，不过滤 chunk 内的非空行
            String chunk = sub.stream()
                    .map(String::trim) // 去掉每行前后的空格
                    .collect(Collectors.joining("\n"))
                    .trim(); // 整体裁剪

            chunks.add(chunk);
        }

        return chunks;
    }

    /**
     * 构造一批向量插入行
     */
    private List<JsonObject> buildRowsForChunks(String documentId,
                                                List<String> chunks) {
        List<JsonObject> rows = new ArrayList<>();
        long now = System.currentTimeMillis();

        for (int i = 0; i < chunks.size(); i++) {
            String chunk = chunks.get(i);
            if (!StringUtils.hasText(chunk)) continue;

            List<Float> emb = embeddingService.embed(chunk);

            JsonObject row = new JsonObject();
            // 每个 chunk 一个独立主键
            row.addProperty("doc_id", IdUtil.getSnowflakeNextIdStr());
            row.add("embedding", floatListToJson(emb));
            row.addProperty("content", chunk);

            JsonObject metadata = new JsonObject();
            metadata.addProperty("documentId", documentId);
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
}
