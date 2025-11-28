package com.nageoffer.ai.ragent.service.rag.vector;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VectorSpaceId {

    /**
     * 逻辑名称：对业务层暴露的名字，跨引擎保持一致
     * 例如：kb_employee_policy
     */
    String logicalName;

    /**
     * 可选：命名空间 / 数据库 / 索引前缀 等
     * 例如：milvus database / ES 索引前缀
     */
    String namespace;
}