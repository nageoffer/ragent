package com.nageoffer.ai.ragent.service.rag.vector;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VectorSpaceSpec {

    /**
     * 向量空间标识
     */
    private VectorSpaceId spaceId;

    /**
     * 备注
     */
    private String remark;
}
