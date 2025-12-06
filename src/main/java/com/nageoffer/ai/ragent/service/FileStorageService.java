package com.nageoffer.ai.ragent.service;

import com.nageoffer.ai.ragent.dto.StoredFileDTO;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;


public interface FileStorageService {

    StoredFileDTO upload(String bucketName, MultipartFile file);

    InputStream openStream(String url);

    void deleteByUrl(String url);
}
