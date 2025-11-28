package com.nageoffer.ai.ragent.rag.embedding;

import com.nageoffer.ai.ragent.service.rag.embedding.SiliconFlowEmbeddingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

@Slf4j
@SpringBootTest
@RequiredArgsConstructor(onConstructor = @__(@Autowired))
public class SiliconFlowEmbeddingServiceTests {

    private final SiliconFlowEmbeddingService embeddingService;

    @Test
    public void embeddingSiliconFlow() {
        List<Float> embedded = embeddingService.embed("测试向量描述");
        System.out.println(embedded);
    }
}
