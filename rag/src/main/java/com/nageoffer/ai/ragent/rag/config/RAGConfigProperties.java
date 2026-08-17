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

package com.nageoffer.ai.ragent.rag.config;

import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * RAG 系统功能配置
 *
 * <p>
 * 用于管理 RAG 系统的各项功能开关，例如查询重写等
 * </p>
 *
 * <pre>
 * 示例配置：
 *
 * rag:
 *   query-rewrite:
 *     enabled: true
 * </pre>
 */
@Data
@Configuration
public class RAGConfigProperties {

    /**
     * 查询重写功能开关
     * <p>
     * 控制是否启用查询重写功能，查询重写可以将用户的查询语句优化为更适合检索的形式
     * 默认值：{@code true}
     */
    @Value("${rag.query-rewrite.enabled:true}")
    private Boolean queryRewriteEnabled;

    /**
     * Rerank 重排序功能开关
     * <p>
     * 控制是否启用 Rerank 后置处理器对召回结果进行重排序
     * 默认值：{@code true}
     */
    @Value("${rag.rerank.enabled:true}")
    private Boolean rerankEnabled;

    /**
     * 上下文元数据富化开关
     * <p>
     * 控制是否在检索末端回表补齐 chunk 的文档归属信息（文档ID/序号/标题），
     * 并在组装上下文时按文档聚合、组内按序号排列、带上文档标题作为内部锚点
     * 关闭后组装退回按检索相关性平铺、不带来源
     * 默认值：{@code true}
     */
    @Value("${rag.context.enrich.enabled:true}")
    private Boolean contextEnrichEnabled;

    /**
     * 回答行内引用（数字角标）开关
     * <p>
     * 开启后为进入上下文的资料注入请求级引用编号，并动态追加行内引用规则，模型在正文末尾输出 {@code [N](#cite-N)}
     * 关闭则不注入编号、不追加行内引用规则，省下这部分系统提示词与上下文开销以降低首字延迟；
     * 文档级来源面板由 SSE 单独下发，不受此开关影响
     * 默认值：{@code false}
     */
    @Value("${rag.citation.enabled:false}")
    private Boolean citationEnabled;
}
