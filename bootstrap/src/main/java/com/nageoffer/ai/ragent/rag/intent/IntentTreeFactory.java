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

package com.nageoffer.ai.ragent.rag.intent;

import com.nageoffer.ai.ragent.rag.enums.IntentKind;

import java.util.ArrayList;
import java.util.List;

import static com.nageoffer.ai.ragent.rag.enums.IntentLevel.CATEGORY;
import static com.nageoffer.ai.ragent.rag.enums.IntentLevel.DOMAIN;
import static com.nageoffer.ai.ragent.rag.enums.IntentLevel.TOPIC;

/**
 * 构造意图识别树
 */
public class IntentTreeFactory {

    private static final String KB_ID_GROUP = "1997855927072321537";
    private static final String KB_ID_BIZ = "1997857139737882625";

    public static List<IntentNode> buildIntentTree() {
        List<IntentNode> roots = new ArrayList<>();

        // ========== 1. 集团信息化 ==========
        IntentNode group = IntentNode.builder()
                .id("group")
                .kbId(KB_ID_GROUP)
                .name("集团信息化")
                .level(DOMAIN)
                .kind(IntentKind.KB)
                .build();

        IntentNode hr = IntentNode.builder()
                .id("group-hr")
                .kbId(KB_ID_GROUP)
                .name("人事")
                .level(CATEGORY)
                .parentId(group.getId())
                .kind(IntentKind.KB)
                .description("招聘、入职、转正、离职、绩效、薪资、考勤、请假等人力资源相关问题")
                .examples(List.of(
                        "请假流程是怎样的？",
                        "试用期多久转正？",
                        "迟到会有什么处罚？"
                ))
                .build();

        IntentNode it = IntentNode.builder()
                .id("group-it")
                .kbId(KB_ID_GROUP)
                .name("IT支持")
                .level(CATEGORY)
                .parentId(group.getId())
                .kind(IntentKind.KB)
                .description("VPN、邮箱、打印机、网络、电脑账号密码、办公软件等 IT 支持相关问题")
                .examples(List.of(
                        "电脑打印机怎么连？",
                        "公司 VPN 连不上怎么办？",
                        "邮箱密码忘了怎么重置？"
                ))
                .build();

        IntentNode finance = IntentNode.builder()
                .id("group-finance")
                .kbId(KB_ID_GROUP)
                .name("财务")
                .level(CATEGORY)
                .parentId(group.getId())
                .kind(IntentKind.KB)
                .description("报销、付款、成本中心、预算等财务相关问题")
                .examples(List.of(
                        "差旅报销需要哪些资料？"
                ))
                .build();

        IntentNode financeInvoice = IntentNode.builder()
                .id("group-finance-invoice")
                .kbId(KB_ID_GROUP)
                .name("发票相关")
                .level(TOPIC)
                .parentId(finance.getId())
                .kind(IntentKind.KB)
                .description("获取公司发票抬头相关信息")
                .examples(List.of(
                        "发票抬头有哪些？"
                ))
                .promptTemplate(FINANCE_INVOICE_PROMPT_TEMPLATE)
                .build();

        finance.setChildren(List.of(financeInvoice));

        group.setChildren(List.of(hr, it, finance));
        roots.add(group);

        // ========== 2. 业务系统 ==========
        IntentNode biz = IntentNode.builder()
                .id("biz")
                .kbId(KB_ID_BIZ)
                .name("业务系统")
                .level(DOMAIN)
                .kind(IntentKind.KB)
                .build();

        // OA 系统
        IntentNode oa = IntentNode.builder()
                .id("biz-oa")
                .kbId(KB_ID_BIZ)
                .name("OA系统")
                .level(CATEGORY)
                .parentId(biz.getId())
                .kind(IntentKind.KB)
                .description("OA 系统相关，例如流程审批、待办、公告、文档中心等")
                .examples(List.of(
                        "OA系统主要提供哪些功能？",
                        "请假审批在哪个菜单？"
                ))
                .build();

        IntentNode oaIntro = IntentNode.builder()
                .id("biz-oa-intro")
                .kbId(KB_ID_BIZ)
                .name("系统介绍")
                .level(TOPIC)
                .parentId(oa.getId())
                .kind(IntentKind.KB)
                .description("OA 系统整体功能说明、主要模块、典型使用场景")
                .examples(List.of(
                        "OA系统是做什么的？"
                ))
                .build();

        IntentNode oaSecurity = IntentNode.builder()
                .id("biz-oa-security")
                .kbId(KB_ID_BIZ)
                .name("数据安全")
                .level(TOPIC)
                .parentId(oa.getId())
                .kind(IntentKind.KB)
                .description("OA系统的数据权限、访问控制、安全审计等相关说明")
                .examples(List.of(
                        "OA系统如何控制不同角色的权限？"
                ))
                .build();

        oa.setChildren(List.of(oaIntro, oaSecurity));

        // 保险系统
        IntentNode ins = IntentNode.builder()
                .id("biz-ins")
                .kbId(KB_ID_BIZ)
                .name("保险系统")
                .level(CATEGORY)
                .parentId(biz.getId())
                .kind(IntentKind.KB)
                .description("保险相关业务系统，如投保、核保、理赔等的功能与架构说明")
                .examples(List.of(
                        "保险系统整体架构是怎样的？"
                ))
                .build();

        IntentNode insIntro = IntentNode.builder()
                .id("biz-ins-intro")
                .kbId(KB_ID_BIZ)
                .name("系统介绍")
                .level(TOPIC)
                .parentId(ins.getId())
                .kind(IntentKind.KB)
                .description("保险系统业务模块说明与主要流程介绍")
                .examples(List.of(
                        "保险系统都包括哪些子系统？"
                ))
                .build();

        IntentNode insArch = IntentNode.builder()
                .id("biz-ins-arch")
                .kbId(KB_ID_BIZ)
                .name("架构设计")
                .level(TOPIC)
                .parentId(ins.getId())
                .kind(IntentKind.KB)
                .description("保险系统的技术架构、服务拆分、数据库设计等")
                .examples(List.of(
                        "保险系统是如何做服务拆分的？"
                ))
                .build();

        IntentNode insSecurity = IntentNode.builder()
                .id("biz-ins-security")
                .kbId(KB_ID_BIZ)
                .name("数据安全")
                .level(TOPIC)
                .parentId(ins.getId())
                .kind(IntentKind.KB)
                .description("保险系统的数据脱敏、权限控制、审计与合规等")
                .examples(List.of(
                        "保险系统的敏感信息如何保护？"
                ))
                .build();

        ins.setChildren(List.of(insIntro, insArch, insSecurity));

        biz.setChildren(List.of(oa, ins));
        roots.add(biz);

        // ========== 3. MCP 实时数据意图查询 ==========

        IntentNode sales = IntentNode.builder()
                .id("sales")
                .name("销售汇总数据统计")
                .level(DOMAIN)
                .kind(IntentKind.MCP) // Domain 可以先标 SYSTEM，仅作语义提示
                .build();

        IntentNode dingTaskSales = IntentNode.builder()
                .id("sales-data")
                .name("销售数据统计")
                .level(CATEGORY)
                .parentId(sales.getId())
                .mcpToolId("sales_query")
                .kind(IntentKind.MCP)
                .promptTemplate(MCP_SALES_DATA_PROMPT_TEMPLATE)
                .paramPromptTemplate(MCP_SALES_DATA_PARAMETER_EXTRACT_PROMPT)
                .description("销售数据统计，如：销售总额、销售量、销售占比、销售趋势、销售预测等")
                .examples(List.of(
                        "销售总额是多少？",
                        "销售量是多少？"
                ))
                .build();

        sales.setChildren(List.of(dingTaskSales));
        roots.add(sales);

        // ========== 4. 系统交互 / 助手说明 ==========
        IntentNode sys = IntentNode.builder()
                .id("sys")
                .name("系统交互")
                .level(DOMAIN)
                .kind(IntentKind.SYSTEM) // Domain 可以先标 SYSTEM，仅作语义提示
                .build();

        // 欢迎 / 问候
        IntentNode welcome = IntentNode.builder()
                .id("sys-welcome")
                .name("欢迎与问候")
                .level(CATEGORY) // 直接作为叶子
                .parentId(sys.getId())
                .description("用户与助手打招呼，如：你好、早上好、hi、在吗 等")
                .examples(List.of(
                        "你好",
                        "hello",
                        "早上好",
                        "在吗",
                        "嗨"
                ))
                .kind(IntentKind.SYSTEM)
                .build();

        // 关于助手
        IntentNode aboutBot = IntentNode.builder()
                .id("sys-about-bot")
                .name("关于助手")
                .level(CATEGORY)
                .parentId(sys.getId())
                .description("询问助手是做什么的、是谁、能做什么等")
                .examples(List.of(
                        "你是谁",
                        "你是做什么的",
                        "你能帮我做什么",
                        "你是什么AI"
                ))
                .kind(IntentKind.SYSTEM)
                .build();

        sys.setChildren(List.of(welcome, aboutBot));
        roots.add(sys);

        // 填充 fullPath
        fillFullPath(roots, null);
        return roots;
    }

    private static void fillFullPath(List<IntentNode> nodes, IntentNode parent) {
        for (IntentNode node : nodes) {
            if (parent == null) {
                node.setFullPath(node.getName());
            } else {
                node.setFullPath(parent.getFullPath() + " > " + node.getName());
            }
            if (node.getChildren() != null && !node.getChildren().isEmpty()) {
                fillFullPath(node.getChildren(), node);
            }
        }
    }

    // =====================常量方法====================

    private static final String FINANCE_INVOICE_PROMPT_TEMPLATE = """
            你是专业的企业发票信息查询助手，现在根据【文档内容】回答用户关于开票信息的问题，并抽取、整理标准化的发票信息。
            
            请严格遵守以下规则：
            
            【字段识别规则】
            1. 文档中的发票相关字段名不一定与标准字段完全一致，请根据语义进行归一映射：
               - 「开票抬头」可对应：开票抬头、发票抬头、公司名称、单位名称、抬头名称等含义相近的字段。
               - 「纳税资质」可对应：纳税人资质、纳税人类别、一般纳税人 / 小规模纳税人说明等。
               - 「纳税人识别号」可对应：纳税人识别号、税号、统一社会信用代码（仅在明确用于开票时）。
               - 「地址、电话」可对应：地址、公司地址、联系地址 + 电话、联系电话、公司电话 等成对出现的信息。
               - 「开户银行、账号」可对应：开户银行、开户行、银行、开户行名称 + 账号、银行账号、账户 等成对出现的信息。
            2. 当文档中字段名与上述标准字段语义相近时，请将其内容归一到对应的标准字段中；不要新增其他字段名。
            
            【回答格式规则】
            1. 回答必须严格基于【文档内容】，不得虚构任何信息，不得凭常识猜测公司名称、税号、地址或银行信息。
            2. 当查询到至少一条发票信息时，必须先输出一段引导语，格式为：
            
               根据您搜索的"【用户问题】"问题，已为您查询到以下发票信息，请查阅：
            
            3. 引导语后空一行，再输出具体的发票信息内容。
            4. 如果查询结果只有一个公司，请输出"单条发票信息"的完整格式化内容。
            5. 如果查询到多个公司，请输出"发票信息列表"，列表中每一项都是完整的一段发票信息，不使用零散的分点描述。
            6. 每条发票信息必须按如下统一格式输出（字段顺序保持一致，每条信息之间空一行）：
            
            开票抬头：xxx
            纳税资质：xxx
            纳税人识别号：xxx
            地址、电话：xxx
            开户银行、账号：xxx
            
            7. 字段有缺失时，必须保留字段名并标注"文档未提供该字段"，例如：
               - 纳税人识别号：文档未提供该字段
            8. 如果文档内没有与用户问题相关的企业，请回答：
               文档未包含相关信息。
            9. 回答中不要添加额外解释或分析，只输出引导语 + 上述格式化的发票信息内容。
            
            【文档内容】
            %s
            
            【用户问题】
            %s
            """;

    public static final String MCP_SALES_DATA_PARAMETER_EXTRACT_PROMPT = """
            Hello，你是一个高度专业且严谨的【工具参数提取器】。
            
            你的唯一任务是：严格按照提供的【工具定义】（Tool Definition）和【参数列表】（Parameters）的约束，从【用户问题】（User Query）中提取所有必要的参数，并以 JSON 格式输出。
            
            ---
            
            ### 核心提取逻辑
            
            1. **数据源限定**：只使用【用户问题】中的信息作为提取来源。
            2. **参数范围限定**：只提取 <parameters> 标签内定义的参数，**禁止**添加任何工具定义中不存在的额外字段。
            3. **必填参数处理（Strict Mode）**：
               - 如果参数是 **"required": true** 且在用户问题中无法找到明确值：
                 - 如果工具定义中提供了 **"default"** 值，请使用该默认值。
                 - 如果**没有**默认值，必须将该参数的值输出为 **null**。
            4. **非必填参数处理**：
               - 如果参数是 **"required": false** 且在用户问题中无法找到明确值：
                 - 如果有默认值，使用默认值。
                 - 如果没有默认值，**请忽略该参数，不要将其包含在最终的 JSON 输出中。**
            
            ### 通用数据类型处理规则
            
            1. **枚举/可选值（Enum）**：
               - **核心原则：意图映射**。将用户口语化、同义或模糊的表达，映射到工具定义中提供的 **enum** 列表中的**最接近的规范值**。
               - 示例：用户说“本周”或“这星期”，枚举值有 "current_week" → 输出 "current_week"。
            
            2. **日期/时间（Date/Time）**：
               - **相对时间**：将“今天”、“昨天”、“上个月”、“今年 Q3”等相对时间表述，**根据当前上下文**映射为工具所需的**规范化格式**或**枚举值**。
               - **时间范围**：如果工具需要 `start_date` 和 `end_date` 两个参数来定义范围，请从一个表述（如“上周”）中提取出两个边界值。
            
            3. **字符串（String）**：
               - **原样提取**：直接截取用户问题中提及的实体名称、人名、地名、产品 ID 等，不需要进行任何转换或缩写，除非工具定义明确要求。
               - **注意**：如果字符串是空或未提及，按必填/非必填规则处理。
            
            4. **数值（Number/Integer）**：
               - **格式统一**：将中文数字（如“三”、“前五”）转换为阿拉伯数字（3, 5）。
               - **提取限定词**：如问题包含“top 10”或“前五名”，提取 `10` 或 `5`。
            
            5. **布尔值（Boolean）**：
               - **肯定**：如“是”、“要”、“开启”、“需要查看” → 映射为 `true`。
               - **否定**：如“否”、“不”、“关闭”、“不需要” → 映射为 `false`。
            
            ---
            
            ### 输入数据与输出格式
            
            请勿在输出 JSON 对象之外添加任何解释、注释或其他文本。
            
            #### 【工具定义】
            <tool_definition>
            %s
            </tool_definition>
            
            #### 【用户问题】
            <user_query>
            %s
            </user_query>
            
            #### 【输出格式（JSON Object Only）】
            
            {"param_name_1": value_1, "param_name_2": value_2, ...}
            
            """;

    private static final String MCP_SALES_DATA_PROMPT_TEMPLATE = """
            Hello，你是专业的企业智能数据助手。系统已调用内部工具获取到了最新的【动态数据】（通常为 JSON 格式）。
            你的任务是将这些结构化数据转化为**商业化、易读的自然语言**回复。
            
            【核心处理规则】
            1. **直接回答**：开门见山地回答用户问题，不要使用“根据数据/JSON显示”这类废话作为开头。
            2. **去技术化**：
               - 将字段名转换为业务术语（例如将 `create_time` 转述为“创建时间”，`status: 1` 转述为“状态正常”）。
               - 除非用户明确询问，否则隐藏内部 ID（如 UUID）、数据库主键或复杂的错误堆栈信息。
            3. **格式化输出（重要）**：
               - **多条数据**：如果数据是列表/数组（超过 2 条），**必须使用 Markdown 表格**展示，表头应为中文。
               - **单条数据**：使用分点（Bullet points）或自然段落清晰表述。
               - **关键指标**：对金额、日期、状态等关键信息进行加粗（**Bold**）处理。
            
            【异常与边界处理】
            1. **数据为空**：如果【动态数据】为 `[]`、`{}` 或 `null`，请直接回答"当前未查询到相关数据记录"。
            2. **报错数据**：如果数据中明显包含 `error`、`code: 500` 或"查询失败"等信息，请用抱歉的口吻告知用户系统暂时无法获取数据，并简述原因（如有）。
            3. **多意图部分匹配**：如果用户问题包含多个子问题，而【动态数据】只能回答其中部分：
               - **先回答能回答的部分**，按正常格式输出数据。
               - **再说明无法回答的部分**，例如："关于『VPN连接方法』，当前未检索到相关知识，建议咨询IT支持。"
               - 不要因为有部分问题无法回答就拒绝回答全部。
            4. **完全不匹配**：仅当【动态数据】与【用户问题】的所有子问题都完全无关时（例如用户问天气，数据却是用户信息），才回答："当前查询到的数据与您的问题不匹配，无法回答。"
            
            【禁止事项】
            - 严禁根据数据内容臆造不存在的结论。
            - 严禁透漏你正在解析 JSON 数据的过程。
            
            {{INTENT_RULES}}
            
            【动态数据】
            %s
            
            【用户问题】
            %s
            """;

}