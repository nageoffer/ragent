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

package com.nageoffer.ai.ragent.knowledge.controller.vo;

import java.util.List;

/**
 * 文档级摄取配置的表单 schema
 * <p>
 * 取代原先的"分块策略列表"。策略枚举在真实链路上不产生任何差异——切法由文档结构唯一决定，
 * 用户只控预算。前端按这份 schema 动态渲染表单，后端加一个参数不需要改前端
 *
 * @param parseProfileLabel      档位选项的字段名，与选项名一起下发，免得两端各存一半改一处漏一处
 * @param parseProfiles          可选解析档位
 * @param parseProfileExtensions 档位真正有区别的文件扩展名，其余格式前端不得展示档位选项
 * @param budgetFields           分块预算字段定义
 * @param wholeDocumentSentinel  整文档不分块的哨兵取值
 */
public record IngestionSpecSchemaVO(String parseProfileLabel,
                                    List<Option> parseProfiles,
                                    List<String> parseProfileExtensions,
                                    List<BudgetField> budgetFields,
                                    int wholeDocumentSentinel) {

    /**
     * @param value 提交值
     * @param label 展示名
     * @param hint  说明
     */
    public record Option(String value, String label, String hint) {
    }

    /**
     * @param key            提交键
     * @param label          展示名
     * @param defaultValue   默认值
     * @param min            允许最小值，越界由构造期拦下
     * @param max            允许最大值，越界由构造期拦下
     * @param recommendedMin 建议区间下界，界面只展示这一段
     * @param recommendedMax 建议区间上界
     * @param hint           一句话说明，常驻展示
     * @param detail         调参说明，前端收进悬浮层，四个字段的长说明并排铺开就是一堵墙
     */
    public record BudgetField(String key, String label, int defaultValue, int min, int max,
                              int recommendedMin, int recommendedMax, String hint, String detail) {
    }
}
