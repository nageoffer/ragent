package com.nageoffer.ai.ragent.core.controller;

import com.nageoffer.ai.ragent.core.service.IndexDocumentService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/documents")
@RequiredArgsConstructor
public class IndexDocumentController {

    private final IndexDocumentService indexDocumentService;

    /**
     * 1）纯文本入库
     */
    @PostMapping("/text")
    public DocumentIndexResult indexText(@RequestBody IndexTextRequest request) {
        return indexDocumentService.indexText(
                request.getTitle(),
                request.getContent(),
                request.getDocumentId()
        );
    }

    /**
     * 2）文件入库：PDF / Markdown / Doc / Docx
     * <p>
     * curl 示例：
     * curl -F "file=@/path/to/a.pdf" http://localhost:8080/api/documents/file
     */
    @PostMapping(
            value = "/file",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public DocumentIndexResult indexFile(@RequestPart("file") MultipartFile file,
                                         @RequestParam(value = "documentId", required = false) String documentId) {
        return indexDocumentService.indexFile(file, documentId);
    }

    /**
     * 3）查询文档所有 chunk
     */
    @GetMapping("/{documentId}")
    public List<DocumentChunk> getDocument(@PathVariable String documentId) {
        return indexDocumentService.getDocumentChunks(documentId);
    }

    /**
     * 4）删除整个文档（所有向量）
     */
    @DeleteMapping("/{documentId}")
    public void deleteDocument(@PathVariable String documentId) {
        indexDocumentService.deleteDocument(documentId);
    }

    /**
     * 5）更新文档：先删旧文档，再用新文件重建向量
     */
    @PutMapping(
            value = "/{documentId}",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public DocumentIndexResult updateDocument(@PathVariable String documentId,
                                              @RequestPart("file") MultipartFile file) {
        return indexDocumentService.reindexDocument(documentId, file);
    }

    // ========== DTO ==========

    @Data
    public static class IndexTextRequest {
        /**
         * 可选标题，会拼在正文前面
         */
        private String title;
        /**
         * 实际文本内容
         */
        private String content;
        /**
         * 可选：指定业务文档ID，不传则生成新的
         */
        private String documentId;
    }
}
