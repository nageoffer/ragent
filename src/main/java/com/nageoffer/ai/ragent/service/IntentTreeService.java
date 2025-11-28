package com.nageoffer.ai.ragent.service;


import com.baomidou.mybatisplus.extension.service.IService;
import com.nageoffer.ai.ragent.dao.entity.IntentNodeDO;
import com.nageoffer.ai.ragent.dto.kb.IntentNodeCreateReqDTO;
import com.nageoffer.ai.ragent.dto.kb.IntentNodeTreeRespDTO;
import com.nageoffer.ai.ragent.dto.kb.IntentNodeUpdateReqDTO;

import java.util.List;

public interface IntentTreeService extends IService<IntentNodeDO> {

    /**
     * 查询整棵意图树（包含 RAG + SYSTEM）
     */
    List<IntentNodeTreeRespDTO> getFullTree();

    /**
     * 新增节点
     */
    String createNode(IntentNodeCreateReqDTO requestParam);

    /**
     * 更新节点
     */
    void updateNode(Long id, IntentNodeUpdateReqDTO requestParam);

    /**
     * 删除节点（逻辑删除）
     */
    void deleteNode(String id);

    /**
     * 从 IntentTreeFactory 初始化全量 Tree 到数据库
     */
    int initFromFactory();
}
