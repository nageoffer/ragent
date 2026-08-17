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

package com.nageoffer.ai.ragent.knowledge.support;

import com.nageoffer.ai.ragent.core.chunk.model.ChunkBudget;
import com.nageoffer.ai.ragent.core.parser.registry.ParseProfile;
import com.nageoffer.ai.ragent.core.parser.registry.ParserRegistry;
import com.nageoffer.ai.ragent.framework.exception.ServiceException;
import com.nageoffer.ai.ragent.knowledge.controller.vo.IngestionSpecSchemaVO;
import com.nageoffer.ai.ragent.rag.util.DisplayType;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 文档级摄取配置的表单 schema 下发：单点
 * <p>
 * 与 {@link IngestionSpecCodec} 同居一包，说的是同一份 spec 的两面——一个管怎么读写校验，一个管它
 * 长什么样、每个字段该填多少，故线路上的键名与哨兵值一律取自 codec，不在此另立第二份；只暴露用户
 * 真正能控的解析档位与块预算，取值范围一并下发，前端不必自己维护一份校验规则
 */
@Component
@RequiredArgsConstructor
public class IngestionSpecSchemaProvider {

    /**
     * 档位选项在界面上的字段名：命名操作人员看得见的事实
     * <p>
     * "快速 / 保真"命名的是引擎的成本换保真度权衡轴，而操作人员手上只有一份文件，他答不出"我需要
     * 多少保真度"，只答得出"这张表有没有合并单元格"，所以直接问表格是什么结构
     */
    private static final String PARSE_PROFILE_LABEL = "表格结构";

    private final ParserRegistry parserRegistry;

    /**
     * 启动期自检：档位文案只覆盖表格类
     * <p>
     * 下面那两句文案说的是合并单元格与多层表头，而 {@link ParserRegistry#profileSensitiveMimeTypes()}
     * 今天只会返回 Excel；哪天有非表格格式认领了非兜底档，这份文案就开始对着一份 PDF 谈表头，故启动
     * 即失败——那时要么改文案，要么把它挪到认领该档位的解析器上按格式分别下发
     */
    @PostConstruct
    void checkProfileCopyCoversAllSensitiveFormats() {
        List<String> unexpected = parserRegistry.profileSensitiveMimeTypes().stream()
                .filter(mime -> !DisplayType.of(null, mime).isTabular())
                .toList();
        if (!unexpected.isEmpty()) {
            throw new ServiceException("档位文案只覆盖表格类，以下 MIME 已认领非兜底档却不是表格，文案需跟着改："
                    + String.join(", ", unexpected));
        }
    }

    /**
     * 装配表单 schema
     * <p>
     * 取值范围的权威在 {@link ChunkBudget} 的构造期，此处只是把同一份数字告诉前端；档位适用的格式清单
     * 从注册表推导、不在任何一端写死，{@code (MIME × 档位)} 表里两档命中同一个解析器的格式，档位对它是
     * 空操作、选项必须藏起来；字段名与选项名一并下发，这三段文案要互相说得通（"表格结构"配"规整 / 复杂
     * 表格"），拆到两端各存一半就是改一处漏一处
     */
    public IngestionSpecSchemaVO describe() {
        ChunkBudget defaults = ChunkBudget.defaults();
        List<IngestionSpecSchemaVO.Option> profiles = List.of(
                new IngestionSpecSchemaVO.Option(ParseProfile.FAST.getCode(), "规整表格",
                        "一行一条记录、表头只有一层，秒级完成"),
                new IngestionSpecSchemaVO.Option(ParseProfile.FIDELITY.getCode(), "复杂表格",
                        "有合并单元格、多层表头或跨页表格；需要数十秒，走外部解析服务"));
        List<String> profileExtensions = parserRegistry.profileSensitiveMimeTypes().stream()
                .map(mime -> DisplayType.of(null, mime))
                .filter(type -> type != DisplayType.OTHER)
                .flatMap(type -> type.extensions().stream())
                .distinct()
                .sorted()
                .toList();
        // hint 答“这个数是什么”、detail 答“调大调小会怎样”，都不是把字段名换句话说一遍；
        List<IngestionSpecSchemaVO.BudgetField> fields = List.of(
                new IngestionSpecSchemaVO.BudgetField(IngestionSpecCodec.KEY_MAX_CHARS, "块大小", defaults.maxChars(),
                        1, ChunkBudget.MAX_CHARS_LIMIT, 512, ChunkBudget.MAX_CHARS_LIMIT,
                        "一段目标放多少字",
                        "这是目标，不是硬上限。为保住整章或整张表不被切开，一段最多能撑到「块大小 × 结构容忍倍数」。"
                                + "调小则检索更精准，但每段带的上下文更少"),
                new IngestionSpecSchemaVO.BudgetField(IngestionSpecCodec.KEY_OVERLAP_CHARS, "块重叠", defaults.overlapChars(),
                        0, ChunkBudget.MAX_CHARS_LIMIT - 1, 64, 1024,
                        "相邻两段重复多少字",
                        "只在单个段落超过块大小、必须拦腰切开时才生效，多数段落到不了这一步。它同时是切口回退找"
                                + "句号的最大距离，填太小会切在句子中间。默认取块大小的 1/" + ChunkBudget.OVERLAP_DIVISOR
                                + "，且必须小于块大小"),
                new IngestionSpecSchemaVO.BudgetField(IngestionSpecCodec.KEY_ROWS_PER_CHUNK, "表格每块行数", defaults.rowsPerChunk(),
                        1, ChunkBudget.ROWS_PER_CHUNK_LIMIT, 20, 50,
                        "表格每段放多少数据行",
                        "列多、每格字数长的表要调小"),
                new IngestionSpecSchemaVO.BudgetField(IngestionSpecCodec.KEY_TOLERANCE_FACTOR, "结构容忍倍数",
                        defaults.toleranceFactor(), 1, ChunkBudget.TOLERANCE_FACTOR_LIMIT, 2, 4,
                        "一段最多可超出块大小的倍数",
                        "为保住整章、整张表、整个代码块不被切开，允许一段撑到块大小的几倍。填 1 就是严格按块大小切，"
                                + "章节会被切碎；调大则块更完整，但检索更粗、单次问答塞给模型的上下文也更多"));
        return new IngestionSpecSchemaVO(
                PARSE_PROFILE_LABEL, profiles, profileExtensions, fields, IngestionSpecCodec.WHOLE_DOCUMENT_SENTINEL);
    }
}
