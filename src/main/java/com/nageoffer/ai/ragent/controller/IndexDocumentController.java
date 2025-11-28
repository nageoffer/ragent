package com.nageoffer.ai.ragent.controller;

import com.nageoffer.ai.ragent.dto.DocumentChunk;
import com.nageoffer.ai.ragent.dto.DocumentIndexResult;
import com.nageoffer.ai.ragent.service.IndexDocumentService;
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
     * 文件入库：PDF / Markdown / Doc / Docx
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
     * 查询文档所有 chunk
     */
    @GetMapping("/{documentId}")
    public List<DocumentChunk> getDocument(@PathVariable String documentId) {
        return indexDocumentService.getDocumentChunks(documentId);
    }

    /**
     * 删除整个文档（所有向量）
     */
    @DeleteMapping("/{documentId}")
    public void deleteDocument(@PathVariable String documentId) {
        indexDocumentService.deleteDocument(documentId);
    }

    /**
     * 更新文档：先删旧文档，再用新文件重建向量
     */
    @PutMapping(
            value = "/{documentId}",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public DocumentIndexResult updateDocument(@PathVariable String documentId,
                                              @RequestPart("file") MultipartFile file) {
        return indexDocumentService.reindexDocument(documentId, file);
    }
}
