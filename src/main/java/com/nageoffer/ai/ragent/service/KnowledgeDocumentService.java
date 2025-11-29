package com.nageoffer.ai.ragent.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.nageoffer.ai.ragent.controller.vo.KnowledgeDocumentVO;
import org.springframework.web.multipart.MultipartFile;

public interface KnowledgeDocumentService {

    KnowledgeDocumentVO upload(String kbId, MultipartFile file);

    void startChunk(String kbId, String docId);

    void delete(String kbId, String docId, boolean purgeVectors);

    KnowledgeDocumentVO get(String kbId, String docId);

    IPage<KnowledgeDocumentVO> page(String kbId, Page<KnowledgeDocumentVO> page, String status, String keyword);

    void enable(String kbId, String docId, boolean enabled);
}
