package com.nageoffer.ai.ragent.core.dto.rag;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RAGAnswer {

    private String question;

    private List<RAGHit> hits;

    private String answer;
}
