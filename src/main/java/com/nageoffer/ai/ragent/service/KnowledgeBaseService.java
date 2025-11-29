package com.nageoffer.ai.ragent.service;

import com.nageoffer.ai.ragent.dao.entity.KnowledgeBaseDO;
import com.nageoffer.ai.ragent.controller.request.KnowledgeBaseCreateRequest;
import com.nageoffer.ai.ragent.controller.request.KnowledgeBaseUpdateRequest;

public interface KnowledgeBaseService {

    String create(KnowledgeBaseCreateRequest requestParam);

    void update(KnowledgeBaseUpdateRequest req);

    void delete(String id);

    KnowledgeBaseDO getById(String id);
}
