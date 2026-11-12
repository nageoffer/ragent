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

import cn.hutool.core.map.MapUtil;
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.mcp.config.McpToolAnnotations;
import com.nageoffer.ai.ragent.mcp.dao.mapper.ProductMapper;
import com.nageoffer.ai.ragent.mcp.dao.result.ProductSummaryResult;
import com.nageoffer.ai.ragent.mcp.dao.result.SkuDetailResult;
import com.nageoffer.ai.ragent.mcp.dao.mapper.ProductSkuMapper;
import com.nageoffer.ai.ragent.mcp.executor.McpToolResults;
import com.nageoffer.ai.ragent.mcp.executor.McpToolSchema;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.nageoffer.ai.ragent.mcp.executor.McpToolSchema.string;
import static com.nageoffer.ai.ragent.mcp.executor.McpToolSchema.integer;
import static com.nageoffer.ai.ragent.mcp.executor.McpToolSchema.number;

/**
 * 商品筛选，比特严选唯一不认登录态的工具
 * <p>
 * 商品目录对谁都一样，圈不出「你的商品」，所以这里不要身份也不按 user_id 过滤
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BitProductSearchMcpExecutor {

    private static final String TOOL_ID = "search_product";

    private static final int DEFAULT_LIMIT = 10;
    private static final int MAX_LIMIT = 20;

    /**
     * 下钻到配置时的条数上限，一款最多能有六十几个容量颜色组合
     */
    private static final int MAX_SKU_DETAIL = 40;

    private static final List<String> CATEGORIES =
            List.of("手机", "平板", "电脑", "智能手表", "耳机音频", "智能家居", "空间计算", "配件");

    private static final List<String> SORTS = List.of("price_asc", "price_desc");

    private final ProductMapper productMapper;
    private final ProductSkuMapper productSkuMapper;

    @Bean
    public McpServerFeatures.SyncToolSpecification searchProductToolSpecification() {
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(buildTool())
                .callHandler((exchange, request) -> handleCall(request))
                .build();
    }

    private Tool buildTool() {
        JsonSchema inputSchema = McpToolSchema.object()
                .optional(string("spuCodes", "按商品款查，多个用逗号分隔，如 SPU-IPHONE-18-PRO；"
                        + "返回该款全部可选配置与各自的下单编码，用户说定了要哪个容量哪个颜色时用它取编码"))
                .optional(string("skuCodes", "按下单编码精确查，多个用逗号分隔，如 MJTK4CH/A；"
                        + "填了它其余筛选条件不生效，已下架的也一起返回"))
                .optional(string("category", "一级品类")
                        .options(CATEGORIES))
                .optional(string("keyword", "关键词，在商品名、子品类、标签和卖点里模糊匹配"))
                .optional(string("tags", "标签，多个用逗号分隔，需全部命中，如 长续航,大字体"))
                .optional(number("minPrice", "价格下限，单位元"))
                .optional(number("maxPrice", "价格上限，单位元"))
                .optional(string("sort", "排序：price_asc 价格升序、price_desc 价格降序")
                        .options(SORTS)
                        .defaultTo("price_asc"))
                .optional(integer("limit", "返回条数，默认 10，最多 20")
                        .defaultTo(DEFAULT_LIMIT))
                .build();

        return Tool.builder()
                .name(TOOL_ID)
                .description("检索比特严选在售商品，支持按品类、价格区间、标签、关键词筛选。"
                        + "按条件筛选时按商品款返回，一款一条，给出价格区间与可选配置数；"
                        + "要具体到某个容量某个颜色时，用 spuCodes 取该款的全部配置及其下单编码。"
                        + "推荐商品前先用它确认款名与现价，参数规格再用返回的款名去知识库检索，"
                        + "不要凭印象报价格和配置")
                .inputSchema(inputSchema)
                .annotations(McpToolAnnotations.READ_ONLY)
                .build();
    }

    private CallToolResult handleCall(CallToolRequest request) {
        long startMs = System.currentTimeMillis();
        try {
            Map<String, Object> args = McpToolResults.args(request);
            Criteria criteria = new Criteria(
                    BitToolSupport.csv(MapUtil.getStr(args, "spuCodes")),
                    BitToolSupport.csv(MapUtil.getStr(args, "skuCodes")),
                    StrUtil.trimToNull(MapUtil.getStr(args, "category")),
                    StrUtil.trimToNull(MapUtil.getStr(args, "keyword")),
                    BitToolSupport.csv(MapUtil.getStr(args, "tags")),
                    BitToolSupport.decimal(MapUtil.getStr(args, "minPrice")),
                    BitToolSupport.decimal(MapUtil.getStr(args, "maxPrice")),
                    MapUtil.getStr(args, "sort"),
                    BitToolSupport.limit(MapUtil.getInt(args, "limit"), DEFAULT_LIMIT, MAX_LIMIT)
            );

            String result = criteria.bySku() ? renderSkus(criteria, querySkus(criteria))
                    : renderSpus(criteria, querySpus(criteria));

            log.info("MCP 工具调用完成, toolId={}, spuCodes={}, skuCodes={}, category={}, keyword={}, elapsed={}ms",
                    TOOL_ID, criteria.spuCodes(), criteria.skuCodes(), criteria.category(), criteria.keyword(),
                    System.currentTimeMillis() - startMs);
            return McpToolResults.success(result);
        } catch (Exception e) {
            log.error("MCP 工具调用失败, toolId={}, elapsed={}ms",
                    TOOL_ID, System.currentTimeMillis() - startMs, e);
            return McpToolResults.failure("商品查询", e);
        }
    }

    private List<SkuDetailResult> querySkus(Criteria criteria) {
        return productSkuMapper.selectDetails(criteria.spuCodes(), criteria.skuCodes(),
                "price_desc".equals(criteria.sort()), MAX_SKU_DETAIL + 1);
    }

    private List<ProductSummaryResult> querySpus(Criteria criteria) {
        return productMapper.selectSummaries(criteria.category(), criteria.keyword(), criteria.tags(),
                criteria.minPrice(), criteria.maxPrice(),
                "price_desc".equals(criteria.sort()), criteria.limit());
    }

    private String renderSpus(Criteria criteria, List<ProductSummaryResult> spus) {
        if (spus.isEmpty()) {
            return "没有符合条件的商品，可以放宽价格区间或换个品类再试";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("【命中 %d 款】%s%n", spus.size(), describe(criteria)));
        for (int i = 0; i < spus.size(); i++) {
            ProductSummaryResult spu = spus.get(i);
            sb.append(String.format("%n%d. %s（%s）%n", i + 1, spu.getName(), spu.getSpuCode()));
            sb.append(String.format("   品类: %s / %s | 品牌: %s（%s）%n", spu.getCategory(),
                    spu.getSubCategory() == null ? BitToolSupport.EMPTY_FIELD : spu.getSubCategory(),
                    spu.getBrand(), spu.getBrandOwner()));
            sb.append(String.format("   价格: %s | %s%n", priceRange(spu),
                    BitToolSupport.stockLabel("在售", spu.getStock())));
            sb.append(String.format("   可选配置: %d 种，要具体到容量颜色请用 spuCodes=%s 再查一次%n",
                    spu.getSkuCount(), spu.getSpuCode()));
            sb.append(String.format("   规格: %s%n", BitToolSupport.specs(spu.getSpecs())));
            sb.append(String.format("   标签: %s%n",
                    spu.getTags() == null ? BitToolSupport.EMPTY_FIELD : spu.getTags()));
            sb.append(String.format("   卖点: %s%n",
                    spu.getSellingPoint() == null ? BitToolSupport.EMPTY_FIELD : spu.getSellingPoint()));
        }
        return sb.toString().trim();
    }

    private String renderSkus(Criteria criteria, List<SkuDetailResult> skus) {
        if (skus.isEmpty()) {
            return "没有找到对应的商品配置，请确认款编码或下单编码是否正确";
        }
        boolean truncated = skus.size() > MAX_SKU_DETAIL;
        List<SkuDetailResult> shown = truncated ? skus.subList(0, MAX_SKU_DETAIL) : skus;

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("【%d 个配置】%s%n", shown.size(), describe(criteria)));
        String currentSpu = null;
        for (SkuDetailResult sku : shown) {
            if (!sku.getSpuName().equals(currentSpu)) {
                currentSpu = sku.getSpuName();
                sb.append(String.format("%n■ %s（%s / %s）%n", currentSpu, sku.getCategory(),
                        sku.getSubCategory() == null ? BitToolSupport.EMPTY_FIELD : sku.getSubCategory()));
                sb.append(String.format("   款级规格: %s%n", BitToolSupport.specs(sku.getSpuSpecs())));
            }
            sb.append(String.format("   %s | %s | %s | 下单编码 %s%n",
                    BitToolSupport.specs(sku.getSpecs()), BitToolSupport.money(sku.getPrice()),
                    BitToolSupport.stockLabel(sku.getStatus(), sku.getStock()), sku.getSkuCode()));
        }
        if (truncated) {
            sb.append(String.format("%n（配置较多，只列出前 %d 个，可缩小到单个款再查）%n", MAX_SKU_DETAIL));
        }
        return sb.toString().trim();
    }

    private String priceRange(ProductSummaryResult summary) {
        BigDecimal min = summary.getMinPrice();
        return min.compareTo(summary.getMaxPrice()) == 0
                ? BitToolSupport.money(min)
                : BitToolSupport.money(min) + " 起，最高 " + BitToolSupport.money(summary.getMaxPrice());
    }

    /**
     * 把生效的筛选条件回显出来，模型才知道这批结果是按什么筛的
     */
    private String describe(Criteria criteria) {
        if (!criteria.spuCodes().isEmpty()) {
            return "按款查配置: " + String.join("、", criteria.spuCodes());
        }
        if (!criteria.skuCodes().isEmpty()) {
            return "按下单编码精确查: " + String.join("、", criteria.skuCodes());
        }
        List<String> parts = new ArrayList<>();
        parts.add("仅在售");
        if (criteria.category() != null) {
            parts.add("品类 " + criteria.category());
        }
        if (criteria.keyword() != null) {
            parts.add("关键词 " + criteria.keyword());
        }
        if (!criteria.tags().isEmpty()) {
            parts.add("标签 " + String.join("+", criteria.tags()));
        }
        if (criteria.minPrice() != null || criteria.maxPrice() != null) {
            parts.add(String.format("价格 %s ~ %s",
                    criteria.minPrice() == null ? "不限" : BitToolSupport.money(criteria.minPrice()),
                    criteria.maxPrice() == null ? "不限" : BitToolSupport.money(criteria.maxPrice())));
        }
        parts.add("price_desc".equals(criteria.sort()) ? "价格降序" : "价格升序");
        return String.join("，", parts);
    }

    private record Criteria(List<String> spuCodes, List<String> skuCodes, String category, String keyword,
                            List<String> tags, BigDecimal minPrice, BigDecimal maxPrice, String sort, int limit) {

        /**
         * 点了名才走配置粒度，否则一律按款聚合，免得同一款的十几个配置把结果刷满
         */
        boolean bySku() {
            return !spuCodes.isEmpty() || !skuCodes.isEmpty();
        }
    }


}
