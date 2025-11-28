package com.nageoffer.ai.ragent.service.rag.chunk;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Chunk {

    private Integer index;

    private String content;
}
