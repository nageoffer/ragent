package com.nageoffer.ai.ragent.core.service.impl;

import com.nageoffer.ai.ragent.core.service.FileStorageService;
import lombok.SneakyThrows;
import org.apache.tika.Tika;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.FileSystemUtils;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

@Service
public class LocalFileStorageService implements FileStorageService {

    @Value("${kb.storage.root:/data/kb}")
    private String rootDir;

    private static final Tika TIKA = new Tika();

    @Override
    @SneakyThrows
    public StoredFile save(String kbId, MultipartFile file) {
        String suffix = extractSuffix(file.getOriginalFilename());
        String newName = UUID.randomUUID().toString().replace("-", "") + (suffix.isBlank() ? "" : "." + suffix);

        Path dir = Path.of(rootDir, String.valueOf(kbId));
        Files.createDirectories(dir);

        Path target = dir.resolve(newName);
        file.transferTo(target);

        long size = Files.size(target);
        String type = TIKA.detect(target);

        // 简单返回本地 URL（真实场景可换成 MinIO/OSS 的外链）
        String url = target.toAbsolutePath().toString();
        return new StoredFile(url, normalizeType(type, suffix), size, file.getOriginalFilename());
    }

    @Override
    public Path localPathFromUrl(String url) {
        return Path.of(url);
    }

    @Override
    @SneakyThrows
    public void deleteByUrl(String url) {
        FileSystemUtils.deleteRecursively(Path.of(url));
    }

    private String extractSuffix(String name) {
        if (name == null) return "";
        int i = name.lastIndexOf('.');
        return i > -1 ? name.substring(i + 1).toLowerCase() : "";
    }

    private String normalizeType(String detected, String suffix) {
        // 将 Tika 的 MIME 转换为表里更通俗的 file_type（可按需扩展）
        return switch ((suffix == null ? "" : suffix)) {
            case "md", "markdown" -> "markdown";
            case "pdf" -> "pdf";
            case "doc", "docx" -> "docx";
            case "txt" -> "txt";
            default -> detected;
        };
    }
}
