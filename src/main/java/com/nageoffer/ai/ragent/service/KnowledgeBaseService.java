package com.nageoffer.ai.ragent.service;

import com.nageoffer.ai.ragent.dao.entity.KnowledgeBaseDO;
import com.nageoffer.ai.ragent.dto.kb.KnowledgeBaseCreateReqDTO;
import com.nageoffer.ai.ragent.dto.kb.KnowledgeBaseUpdateReqDTO;

public interface KnowledgeBaseService {

    String create(KnowledgeBaseCreateReqDTO requestParam);

    void update(KnowledgeBaseUpdateReqDTO req);

    void delete(String id);

    KnowledgeBaseDO getById(String id);
}
