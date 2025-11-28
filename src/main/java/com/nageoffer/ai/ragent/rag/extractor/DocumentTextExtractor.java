package com.nageoffer.ai.ragent.rag.extractor;

import java.nio.file.Path;

public interface DocumentTextExtractor {

    String extract(Path file, String originalFilename);
}
