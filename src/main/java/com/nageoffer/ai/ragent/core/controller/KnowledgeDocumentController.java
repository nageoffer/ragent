package com.nageoffer.ai.ragent.core.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.nageoffer.ai.ragent.core.dto.kb.KnowledgeDocumentVO;
import com.nageoffer.ai.ragent.core.framework.convention.Result;
import com.nageoffer.ai.ragent.core.framework.web.Results;
import com.nageoffer.ai.ragent.core.service.KnowledgeDocumentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/knowledge-base/{kbId}/docs")
@RequiredArgsConstructor
@Validated
public class KnowledgeDocumentController {

    private final KnowledgeDocumentService documentService;

    /**
     * 上传文档：入库记录 + 文件落盘，返回文档ID
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<KnowledgeDocumentVO> upload(@PathVariable("kbId") String kbId,
                                      @RequestPart("file") MultipartFile file) {
        return Results.success(documentService.upload(kbId, file));
    }

    /**
     * 开始分块：抽取文本 -> 分块 -> 嵌入并写入向量库
     */
    @PostMapping("/{docId}/chunk")
    public Result<Void> startChunk(@PathVariable("kbId") String kbId,
                             @PathVariable("docId") String docId) {
        documentService.startChunk(kbId, docId);
        return Results.success();
    }

    /**
     * 删除文档：逻辑删除。可选同时删除向量库中该文档的所有 chunk
     */
    @DeleteMapping("/{docId}")
    public Result<Void> delete(@PathVariable("kbId") String kbId,
                         @PathVariable("docId") String docId,
                         @RequestParam(value = "purgeVectors", defaultValue = "true") boolean purgeVectors) {
        documentService.delete(kbId, docId, purgeVectors);
        return Results.success();
    }

    /**
     * 查询文档详情
     */
    @GetMapping("/{docId}")
    public Result<KnowledgeDocumentVO> get(@PathVariable("kbId") String kbId,
                                   @PathVariable("docId") String docId) {
        return Results.success(documentService.get(kbId, docId));
    }

    /**
     * 分页查询文档列表（支持状态/关键字过滤）
     */
    @GetMapping
    public Result<IPage<KnowledgeDocumentVO>> page(@PathVariable("kbId") String kbId,
                                           @RequestParam(value = "pageNo", defaultValue = "1") int pageNo,
                                           @RequestParam(value = "pageSize", defaultValue = "10") int pageSize,
                                           @RequestParam(value = "status", required = false) String status,
                                           @RequestParam(value = "keyword", required = false) String keyword) {
        return Results.success(documentService.page(kbId, new Page<>(pageNo, pageSize), status, keyword));
    }

    /**
     * 启用/禁用文档
     */
    @PatchMapping("/{docId}/enable")
    public Result<Void> enable(@PathVariable("kbId") String kbId,
                               @PathVariable("docId") String docId,
                               @RequestParam("value") boolean enabled) {
        documentService.enable(kbId, docId, enabled);
        return Results.success();
    }
}

