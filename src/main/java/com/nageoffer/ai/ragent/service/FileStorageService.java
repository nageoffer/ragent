package com.nageoffer.ai.ragent.service;

import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;

public interface FileStorageService {

    record StoredFile(String url, String detectedType, long size, String originalFilename) {
    }

    StoredFile save(String kbId, MultipartFile file);

    Path localPathFromUrl(String url);

    void deleteByUrl(String url);
}
