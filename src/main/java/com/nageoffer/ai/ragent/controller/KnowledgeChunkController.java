package com.nageoffer.ai.ragent.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.nageoffer.ai.ragent.controller.request.KnowledgeChunkBatchRequest;
import com.nageoffer.ai.ragent.controller.request.KnowledgeChunkCreateRequest;
import com.nageoffer.ai.ragent.controller.request.KnowledgeChunkPageRequest;
import com.nageoffer.ai.ragent.controller.request.KnowledgeChunkUpdateRequest;
import com.nageoffer.ai.ragent.controller.vo.KnowledgeChunkVO;
import com.nageoffer.ai.ragent.framework.convention.Result;
import com.nageoffer.ai.ragent.framework.web.Results;
import com.nageoffer.ai.ragent.service.KnowledgeChunkService;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 知识库 Chunk 管理接口
 */
@RestController
@RequiredArgsConstructor
@Validated
public class KnowledgeChunkController {

    private final KnowledgeChunkService knowledgeChunkService;

    /**
     * 分页查询 Chunk 列表
     */
    @GetMapping("/knowledge-base/docs/{doc-id}/chunks")
    public Result<IPage<KnowledgeChunkVO>> pageQuery(@PathVariable("doc-id") String docId,
                                                     @Validated KnowledgeChunkPageRequest requestParam) {
        return Results.success(knowledgeChunkService.pageQuery(docId, requestParam));
    }

    /**
     * 新增 Chunk
     */
    @PostMapping("/knowledge-base/docs/{doc-id}/chunks")
    public Result<KnowledgeChunkVO> create(@PathVariable("doc-id") String docId,
                                           @RequestBody KnowledgeChunkCreateRequest request) {
        return Results.success(knowledgeChunkService.create(docId, request));
    }

    /**
     * 更新 Chunk 内容
     */
    @PutMapping("/knowledge-base/docs/{doc-id}/chunks/{chunk-id}")
    public Result<Void> update(@PathVariable("doc-id") String docId,
                               @PathVariable("chunk-id") String chunkId,
                               @RequestBody KnowledgeChunkUpdateRequest request) {
        knowledgeChunkService.update(docId, chunkId, request);
        return Results.success();
    }

    /**
     * 删除 Chunk
     */
    @DeleteMapping("/knowledge-base/docs/{doc-id}/chunks/{chunk-id}")
    public Result<Void> delete(@PathVariable("doc-id") String docId,
                               @PathVariable("chunk-id") String chunkId) {
        knowledgeChunkService.delete(docId, chunkId);
        return Results.success();
    }

    /**
     * 启用单条 Chunk
     */
    @PostMapping("/knowledge-base/docs/{doc-id}/chunks/{chunk-id}/enable")
    public Result<Void> enable(@PathVariable("doc-id") String docId,
                               @PathVariable("chunk-id") String chunkId) {
        knowledgeChunkService.enableChunk(docId, chunkId, true);
        return Results.success();
    }

    /**
     * 禁用单条 Chunk
     */
    @PostMapping("/knowledge-base/docs/{doc-id}/chunks/{chunk-id}/disable")
    public Result<Void> disable(@PathVariable("doc-id") String docId,
                                @PathVariable("chunk-id") String chunkId) {
        knowledgeChunkService.enableChunk(docId, chunkId, false);
        return Results.success();
    }

    /**
     * 批量启用 Chunk
     */
    @PostMapping("/knowledge-base/docs/{doc-id}/chunks/batch-enable")
    public Result<Void> batchEnable(@PathVariable("doc-id") String docId,
                                    @RequestBody(required = false) KnowledgeChunkBatchRequest request) {
        knowledgeChunkService.batchEnable(docId, request);
        return Results.success();
    }

    /**
     * 批量禁用 Chunk
     */
    @PostMapping("/knowledge-base/docs/{doc-id}/chunks/batch-disable")
    public Result<Void> batchDisable(@PathVariable("doc-id") String docId,
                                     @RequestBody(required = false) KnowledgeChunkBatchRequest request) {
        knowledgeChunkService.batchDisable(docId, request);
        return Results.success();
    }

    /**
     * 重建文档向量（以 MySQL enabled=1 的 chunk 为准）
     */
    @PostMapping("/knowledge-base/docs/{doc-id}/chunks/rebuild")
    public Result<Void> rebuild(@PathVariable("doc-id") String docId) {
        knowledgeChunkService.rebuildByDocId(docId);
        return Results.success();
    }
}
