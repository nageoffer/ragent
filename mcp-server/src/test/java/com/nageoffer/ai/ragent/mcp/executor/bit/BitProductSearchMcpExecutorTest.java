/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.ragent.mcp.executor.bit;

import com.nageoffer.ai.ragent.mcp.dao.mapper.ProductMapper;
import com.nageoffer.ai.ragent.mcp.dao.mapper.ProductSkuMapper;
import com.nageoffer.ai.ragent.mcp.dao.result.SkuDetailResult;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BitProductSearchMcpExecutorTest {
    @Test
    void skuLookupReturnsStructuredNamesAlongsideExistingText() {
        ProductSkuMapper skus = mock(ProductSkuMapper.class);
        SkuDetailResult sku = new SkuDetailResult();
        sku.setSkuCode("A");
        sku.setSpuCode("SPU");
        sku.setSpuName("测试手机");
        sku.setName("测试手机 256GB 黑色");
        sku.setCategory("手机");
        sku.setSpecs("{}");
        sku.setSpuSpecs("{}");
        sku.setPrice(BigDecimal.TEN);
        sku.setStock(10);
        sku.setStatus("在售");
        when(skus.selectDetails(anyList(), eq(List.of("A")), anyBoolean(), anyInt()))
                .thenReturn(List.of(sku));
        var executor = new BitProductSearchMcpExecutor(mock(ProductMapper.class), skus);
        var result = executor.searchProductToolSpecification().callHandler().apply(null,
                new CallToolRequest("search_product", Map.of("skuCodes", "A")));
        assertThat(result.isError()).isFalse();
        assertThat(result.structuredContent()).isEqualTo(
                Map.of("productNames", Map.of("A", "测试手机 256GB 黑色")));
        assertThat(result.content().toString()).contains("测试手机", "下单编码 A");
    }

    @Test
    void ordinarySearchKeepsItsExistingResponseFormat() {
        ProductMapper products = mock(ProductMapper.class);
        ProductSkuMapper skus = mock(ProductSkuMapper.class);
        when(products.selectSummaries(any(), any(), anyList(), any(), any(), anyBoolean(), anyInt()))
                .thenReturn(List.of());
        var executor = new BitProductSearchMcpExecutor(products, skus);
        var result = executor.searchProductToolSpecification().callHandler().apply(null,
                new CallToolRequest("search_product", Map.of("keyword", "手机")));
        assertThat(result.isError()).isFalse();
        assertThat(result.structuredContent()).isNull();
        assertThat(result.content().toString()).contains("没有符合条件的商品");
        verifyNoInteractions(skus);
    }

}
