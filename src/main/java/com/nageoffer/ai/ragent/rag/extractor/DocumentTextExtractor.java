package com.nageoffer.ai.ragent.rag.extractor;

import java.io.InputStream;

public interface DocumentTextExtractor {

    String extract(InputStream stream, String fileName);
}
