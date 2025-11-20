package com.nageoffer.ai.ragent.core.dto.rag;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RAGHit {

    private String id;

    private String text;

    private Float score;
}
