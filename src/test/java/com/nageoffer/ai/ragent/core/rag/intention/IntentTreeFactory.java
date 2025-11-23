package com.nageoffer.ai.ragent.core.rag.intention;

import java.util.ArrayList;
import java.util.List;

import static com.nageoffer.ai.ragent.core.rag.intention.IntentNode.Level.CATEGORY;
import static com.nageoffer.ai.ragent.core.rag.intention.IntentNode.Level.DOMAIN;
import static com.nageoffer.ai.ragent.core.rag.intention.IntentNode.Level.TOPIC;

public class IntentTreeFactory {

    public static List<IntentNode> buildIntentTree() {
        List<IntentNode> roots = new ArrayList<>();

        // ========== 1. 集团信息化 ==========
        IntentNode group = IntentNode.builder()
                .id("group")
                .name("集团信息化")
                .level(DOMAIN)
                .description("与集团内部人事、行政、IT支持、财务、公司资质、通讯录、解决方案等相关的企业信息化问题")
                .build();

        IntentNode hr = IntentNode.builder()
                .id("group-hr")
                .name("人事")
                .level(CATEGORY)
                .parentId(group.getId())
                .description("招聘、入职、转正、离职、绩效、薪资、考勤、请假等人力资源相关问题")
                .examples(List.of(
                        "请假流程是怎样的？",
                        "试用期多久转正？",
                        "迟到会有什么处罚？"
                ))
                .build();

        IntentNode admin = IntentNode.builder()
                .id("group-admin")
                .name("行政")
                .level(CATEGORY)
                .parentId(group.getId())
                .description("办公场地、工位、门禁、会议室、办公用品、快递等行政管理相关问题")
                .examples(List.of(
                        "新员工如何申请工位？",
                        "会议室怎么预定？"
                ))
                .build();

        IntentNode it = IntentNode.builder()
                .id("group-it")
                .name("IT支持")
                .level(CATEGORY)
                .parentId(group.getId())
                .description("VPN、邮箱、打印机、网络、电脑账号密码、办公软件等 IT 支持相关问题")
                .examples(List.of(
                        "Mac电脑打印机怎么连？",
                        "公司 VPN 连不上怎么办？",
                        "邮箱密码忘了怎么重置？"
                ))
                .build();

        IntentNode finance = IntentNode.builder()
                .id("group-finance")
                .name("财务")
                .level(CATEGORY)
                .parentId(group.getId())
                .description("报销、发票、付款、成本中心、预算等财务相关问题")
                .examples(List.of(
                        "差旅报销需要哪些资料？",
                        "发票抬头有哪些？"
                ))
                .build();

        IntentNode qualification = IntentNode.builder()
                .id("group-qualification")
                .name("公司资质")
                .level(CATEGORY)
                .parentId(group.getId())
                .description("公司营业执照、软著、认证证书、行业资质等相关问题")
                .examples(List.of(
                        "公司有哪些行业认证？"
                ))
                .build();

        IntentNode contacts = IntentNode.builder()
                .id("group-contacts")
                .name("通讯录")
                .level(CATEGORY)
                .parentId(group.getId())
                .description("内部通讯录、部门负责人联系方式、通用邮箱等")
                .examples(List.of(
                        "如何查询同事电话？",
                        "HR 负责人的邮箱是多少？"
                ))
                .build();

        IntentNode solutions = IntentNode.builder()
                .id("group-solutions")
                .name("解决方案")
                .level(CATEGORY)
                .parentId(group.getId())
                .description("对外售前方案、产品介绍PPT、案例资料、演示脚本等")
                .examples(List.of(
                        "有没有最新的解决方案PPT？"
                ))
                .build();

        group.setChildren(List.of(hr, admin, it, finance, qualification, contacts, solutions));
        roots.add(group);

        // ========== 2. 业务系统 ==========
        IntentNode biz = IntentNode.builder()
                .id("biz")
                .name("业务系统")
                .level(DOMAIN)
                .description("公司内部 OA 系统、保险系统等业务系统的功能介绍、架构设计、数据安全等问题")
                .build();

        // OA 系统
        IntentNode oa = IntentNode.builder()
                .id("biz-oa")
                .name("OA系统")
                .level(CATEGORY)
                .parentId(biz.getId())
                .description("OA 系统相关，例如流程审批、待办、公告、文档中心等")
                .examples(List.of(
                        "OA系统主要提供哪些功能？",
                        "请假审批在哪个菜单？"
                ))
                .build();

        IntentNode oaIntro = IntentNode.builder()
                .id("biz-oa-intro")
                .name("系统介绍")
                .level(TOPIC)
                .parentId(oa.getId())
                .description("OA 系统整体功能说明、主要模块、典型使用场景")
                .examples(List.of(
                        "OA系统是做什么的？"
                ))
                .build();

        IntentNode oaSecurity = IntentNode.builder()
                .id("biz-oa-security")
                .name("数据安全")
                .level(TOPIC)
                .parentId(oa.getId())
                .description("OA系统的数据权限、访问控制、安全审计等相关说明")
                .examples(List.of(
                        "OA系统如何控制不同角色的权限？"
                ))
                .build();

        oa.setChildren(List.of(oaIntro, oaSecurity));

        // 保险系统
        IntentNode ins = IntentNode.builder()
                .id("biz-ins")
                .name("保险系统")
                .level(CATEGORY)
                .parentId(biz.getId())
                .description("保险相关业务系统，如投保、核保、理赔等的功能与架构说明")
                .examples(List.of(
                        "保险系统整体架构是怎样的？"
                ))
                .build();

        IntentNode insIntro = IntentNode.builder()
                .id("biz-ins-intro")
                .name("系统介绍")
                .level(TOPIC)
                .parentId(ins.getId())
                .description("保险系统业务模块说明与主要流程介绍")
                .examples(List.of(
                        "保险系统都包括哪些子系统？"
                ))
                .build();

        IntentNode insArch = IntentNode.builder()
                .id("biz-ins-arch")
                .name("架构设计")
                .level(TOPIC)
                .parentId(ins.getId())
                .description("保险系统的技术架构、服务拆分、数据库设计等")
                .examples(List.of(
                        "保险系统是如何做服务拆分的？"
                ))
                .build();

        IntentNode insSecurity = IntentNode.builder()
                .id("biz-ins-security")
                .name("数据安全")
                .level(TOPIC)
                .parentId(ins.getId())
                .description("保险系统的数据脱敏、权限控制、审计与合规等")
                .examples(List.of(
                        "保险系统的敏感信息如何保护？"
                ))
                .build();

        ins.setChildren(List.of(insIntro, insArch, insSecurity));

        biz.setChildren(List.of(oa, ins));
        roots.add(biz);

        // ========== 3. 中间件环境信息 ==========
        IntentNode mw = IntentNode.builder()
                .id("middleware")
                .name("中间件环境信息")
                .level(DOMAIN)
                .description("Redis、RocketMQ、XXL-Job 等中间件环境的部署信息、连接方式、集群拓扑等")
                .examples(List.of(
                        "测试环境 Redis 地址是多少？",
                        "生产 RocketMQ 控制台在哪？"
                ))
                .build();

        IntentNode redis = IntentNode.builder()
                .id("middleware-redis")
                .name("Redis")
                .level(CATEGORY)
                .parentId(mw.getId())
                .description("Redis 环境信息、连接配置、命名规范等")
                .examples(List.of(
                        "Redis 的连接地址是什么？"
                ))
                .build();

        IntentNode rocketmq = IntentNode.builder()
                .id("middleware-rocketmq")
                .name("RocketMQ")
                .level(CATEGORY)
                .parentId(mw.getId())
                .description("RocketMQ 集群信息、Topic/Group 规范、控制台地址等")
                .examples(List.of(
                        "订单系统的 RocketMQ Topic 叫什么？"
                ))
                .build();

        IntentNode xxlJob = IntentNode.builder()
                .id("middleware-xxljob")
                .name("XXL-Job")
                .level(CATEGORY)
                .parentId(mw.getId())
                .description("XXL-Job 调度中心地址、执行器配置、任务编写规范等")
                .examples(List.of(
                        "XXL-Job 调度中心地址是多少？"
                ))
                .build();

        mw.setChildren(List.of(redis, rocketmq, xxlJob));
        roots.add(mw);

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
}

