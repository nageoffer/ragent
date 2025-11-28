package com.nageoffer.ai.ragent.service.rag.extractor;

import lombok.SneakyThrows;
import org.apache.tika.Tika;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;

@Service
public class TikaDocumentTextExtractor implements DocumentTextExtractor {

    private static final Tika TIKA = new Tika();

    @Override
    @SneakyThrows
    public String extract(Path file, String originalFilename) {
        try (var is = Files.newInputStream(file)) {
            // 方式一：最简
            String raw = TIKA.parseToString(is);
            return cleanup(raw);
        }
    }

    private String cleanup(String s) {
        if (s == null) return "";
        String t = s.replace("\uFEFF", "") // 去掉 BOM
                .replaceAll("[ \\t]+\\n", "\n") // 去尾随空格
                .replaceAll("\\n{3,}", "\n\n")  // 合并过多空行
                .trim();
        return t;
    }
}
