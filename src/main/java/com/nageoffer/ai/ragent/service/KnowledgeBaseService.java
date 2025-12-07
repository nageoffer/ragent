package com.nageoffer.ai.ragent.service;

import com.nageoffer.ai.ragent.controller.request.KnowledgeBaseCreateRequest;
import com.nageoffer.ai.ragent.controller.request.KnowledgeBaseUpdateRequest;
import com.nageoffer.ai.ragent.controller.vo.KnowledgeBaseVO;

public interface KnowledgeBaseService {

    String create(KnowledgeBaseCreateRequest requestParam);

    void update(KnowledgeBaseUpdateRequest requestParam);

    void rename(String kbId, KnowledgeBaseUpdateRequest requestParam);

    void delete(String kbId);

    KnowledgeBaseVO queryById(String kbId);
}
