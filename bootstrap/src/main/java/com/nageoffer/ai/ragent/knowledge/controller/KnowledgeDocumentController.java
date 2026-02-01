/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.ragent.knowledge.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.nageoffer.ai.ragent.knowledge.controller.request.KnowledgeDocumentUploadRequest;
import com.nageoffer.ai.ragent.knowledge.controller.request.KnowledgeDocumentUpdateRequest;
import com.nageoffer.ai.ragent.knowledge.controller.vo.KnowledgeDocumentVO;
import com.nageoffer.ai.ragent.knowledge.controller.vo.KnowledgeDocumentChunkLogVO;
import com.nageoffer.ai.ragent.framework.convention.Result;
import com.nageoffer.ai.ragent.framework.web.Results;
import com.nageoffer.ai.ragent.knowledge.service.KnowledgeDocumentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 知识库文档管理控制器
 * 提供文档的上传、分块、删除、查询、启用/禁用等功能
 */
@RestController
@RequiredArgsConstructor
@Validated
public class KnowledgeDocumentController {

    private final KnowledgeDocumentService documentService;

    /**
     * 上传文档：入库记录 + 文件落盘，返回文档ID
     */
    @PostMapping(value = "/knowledge-base/{kb-id}/docs/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<KnowledgeDocumentVO> upload(@PathVariable("kb-id") String kbId,
                                              @RequestPart(value = "file", required = false) MultipartFile file,
                                              @ModelAttribute KnowledgeDocumentUploadRequest requestParam) {
        return Results.success(documentService.upload(kbId, requestParam, file));
    }

    /**
     * 开始分块：抽取文本 -> 分块 -> 嵌入并写入向量库
     */
    @PostMapping("/knowledge-base/docs/{doc-id}/chunk")
    public Result<Void> startChunk(@PathVariable(value = "doc-id") String docId) {
        documentService.startChunk(docId);
        return Results.success();
    }

    /**
     * 删除文档：逻辑删除。可选同时删除向量库中该文档的所有 chunk
     */
    @DeleteMapping("/knowledge-base/docs/{doc-id}")
    public Result<Void> delete(@PathVariable(value = "doc-id") String docId) {
        documentService.delete(docId);
        return Results.success();
    }

    /**
     * 查询文档详情
     */
    @GetMapping("/knowledge-base/docs/{docId}")
    public Result<KnowledgeDocumentVO> get(@PathVariable String docId) {
        return Results.success(documentService.get(docId));
    }

    /**
     * 更新文档信息
     */
    @PutMapping("/knowledge-base/docs/{docId}")
    public Result<Void> update(@PathVariable String docId,
                               @RequestBody KnowledgeDocumentUpdateRequest requestParam) {
        documentService.update(docId, requestParam);
        return Results.success();
    }

    /**
     * 分页查询文档列表（支持状态/关键字过滤）
     */
    @GetMapping("/knowledge-base/{kb-id}/docs")
    public Result<IPage<KnowledgeDocumentVO>> page(@PathVariable(value = "kb-id") String kbId,
                                                   @RequestParam(value = "pageNo", defaultValue = "1") int pageNo,
                                                   @RequestParam(value = "pageSize", defaultValue = "10") int pageSize,
                                                   @RequestParam(value = "status", required = false) String status,
                                                   @RequestParam(value = "keyword", required = false) String keyword) {
        return Results.success(documentService.page(kbId, new Page<>(pageNo, pageSize), status, keyword));
    }

    /**
     * 分页查询全部文档列表
     */
    @GetMapping("/knowledge-documents")
    public Result<IPage<KnowledgeDocumentVO>> pageAll(@RequestParam(value = "pageNo", defaultValue = "1") int pageNo,
                                                      @RequestParam(value = "pageSize", defaultValue = "10") int pageSize,
                                                      @RequestParam(value = "status", required = false) String status,
                                                      @RequestParam(value = "keyword", required = false) String keyword) {
        return Results.success(documentService.pageAll(new Page<>(pageNo, pageSize), status, keyword));
    }

    /**
     * 启用/禁用文档
     */
    @PatchMapping("/knowledge-base/docs/{docId}/enable")
    public Result<Void> enable(@PathVariable String docId,
                               @RequestParam("value") boolean enabled) {
        documentService.enable(docId, enabled);
        return Results.success();
    }

    /**
     * 查询文档分块日志列表
     */
    @GetMapping("/knowledge-base/docs/{docId}/chunk-logs")
    public Result<IPage<KnowledgeDocumentChunkLogVO>> getChunkLogs(@PathVariable String docId,
                                                                   @RequestParam(value = "pageNo", defaultValue = "1") int pageNo,
                                                                   @RequestParam(value = "pageSize", defaultValue = "10") int pageSize) {
        return Results.success(documentService.getChunkLogs(docId, new Page<>(pageNo, pageSize)));
    }
}
