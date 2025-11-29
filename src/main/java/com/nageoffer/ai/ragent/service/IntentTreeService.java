package com.nageoffer.ai.ragent.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.nageoffer.ai.ragent.dao.entity.IntentNodeDO;
import com.nageoffer.ai.ragent.controller.request.IntentNodeCreateRequest;
import com.nageoffer.ai.ragent.controller.vo.IntentNodeTreeVO;
import com.nageoffer.ai.ragent.controller.request.IntentNodeUpdateRequest;

import java.util.List;

public interface IntentTreeService extends IService<IntentNodeDO> {

    /**
     * 查询整棵意图树（包含 RAG + SYSTEM）
     */
    List<IntentNodeTreeVO> getFullTree();

    /**
     * 新增节点
     */
    String createNode(IntentNodeCreateRequest requestParam);

    /**
     * 更新节点
     */
    void updateNode(Long id, IntentNodeUpdateRequest requestParam);

    /**
     * 删除节点（逻辑删除）
     */
    void deleteNode(String id);

    /**
     * 从 IntentTreeFactory 初始化全量 Tree 到数据库
     */
    int initFromFactory();
}
