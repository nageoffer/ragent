package com.nageoffer.ai.ragent.controller.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IntentNodeTreeVO {

    private Long id;
    private String intentCode;
    private String name;
    private Integer level;
    private String parentCode;
    private String description;
    private String examples;
    private String collectionName;
    private Integer kind;
    private Integer sortOrder;
    private Integer enabled;

    private List<IntentNodeTreeVO> children;
}
