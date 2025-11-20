package com.nageoffer.ai.ragent.dto;

import lombok.Data;

@Data
public class QueryRequest {

    private String question;

    // 可选，默认 3
    private Integer topK;
}
