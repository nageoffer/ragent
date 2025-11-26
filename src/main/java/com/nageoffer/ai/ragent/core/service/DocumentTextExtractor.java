package com.nageoffer.ai.ragent.core.service;

import java.nio.file.Path;

public interface DocumentTextExtractor {

    String extract(Path file, String originalFilename);
}
