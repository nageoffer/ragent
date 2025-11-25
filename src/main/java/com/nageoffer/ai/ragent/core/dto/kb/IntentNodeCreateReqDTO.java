package com.nageoffer.ai.ragent.core.dto.kb;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IntentNodeCreateReqDTO {

    private String intentCode;
    private String name;
    /**
     * 1=DOMAIN,2=CATEGORY,3=TOPIC
     */
    private Integer level;
    private String parentCode;
    private String description;
    private List<String> examples;
    private String collectionName;
    private Integer kind;
    private Integer sortOrder;
    private Integer enabled;
}
