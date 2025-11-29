package com.nageoffer.ai.ragent.controller.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IntentNodeUpdateRequest {

    private String name;
    private Integer level;
    private String parentCode;
    private String description;
    private List<String> examples;
    private String collectionName;
    private Integer kind;
    private Integer sortOrder;
    private Integer enabled;
}
