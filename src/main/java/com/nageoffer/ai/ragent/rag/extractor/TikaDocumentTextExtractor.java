package com.nageoffer.ai.ragent.rag.extractor;

import com.nageoffer.ai.ragent.framework.exception.ServiceException;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.springframework.stereotype.Service;

import java.io.InputStream;

@Slf4j
@Service
public class TikaDocumentTextExtractor implements DocumentTextExtractor {

    private static final Tika TIKA = new Tika();

    @Override
    @SneakyThrows
    public String extract(InputStream stream, String fileName) {
        try {
            String text = TIKA.parseToString(stream);
            return cleanup(text);
        } catch (Exception e) {
            log.error("从文件中提取文本内容失败: {}", fileName, e);
            throw new ServiceException("解析文件失败: " + fileName);
        }
    }

    private String cleanup(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }

        return text.replace("\uFEFF", "")
                .replaceAll("[ \\t]+\\n", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }
}
