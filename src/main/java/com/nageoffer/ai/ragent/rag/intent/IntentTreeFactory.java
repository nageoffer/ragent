
package com.nageoffer.ai.ragent.rag.intent;

import com.nageoffer.ai.ragent.enums.IntentKind;

import java.util.ArrayList;
import java.util.List;

import static com.nageoffer.ai.ragent.enums.IntentLevel.CATEGORY;
import static com.nageoffer.ai.ragent.enums.IntentLevel.DOMAIN;
import static com.nageoffer.ai.ragent.enums.IntentLevel.TOPIC;

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
}

