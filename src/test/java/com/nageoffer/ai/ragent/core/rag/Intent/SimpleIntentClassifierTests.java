package com.nageoffer.ai.ragent.core.rag.Intent;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.nageoffer.ai.ragent.core.service.rag.chat.LLMService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

@Slf4j
@SpringBootTest
@RequiredArgsConstructor(onConstructor = @__(@Autowired))
public class SimpleIntentClassifierTests {

    private final LLMService llmService;

    private final Gson gson = new Gson();

    @Test
    public void intentClassifier() {
        String question = "Mac电脑打印机怎么连？";
        String prompt = buildIntentPrompt(question);

        long start = System.nanoTime();
        String chat = llmService.chat(prompt);
        long end = System.nanoTime();

        System.out.println(chat);
        System.out.println("Total time: " + ((end - start) / 1_000_000.0) + " ms");
    }

    private String buildIntentPrompt(String question) {
        return """
                你是一个企业内部知识库 RAG 系统中的【意图识别模块】。
                你的任务是：根据用户提出的问题，判断该问题属于哪一类业务意图，
                并给出推荐的集合名称和元数据过滤条件，以便向量数据库检索。
                
                请特别注意：
                1. 需要支持【模糊问法和同义词】，例如：
                   - “上班时间”“几点上班”“每天要工作多久” → 归类为 ATTENDANCE（考勤/工作时间）
                   - “能不能多休几天”“休假怎么安排” → ATTENDANCE
                   - “发工资时间”“年终奖怎么算”“涨工资流程” → COMPENSATION
                   - “面试流程是怎样的”“校招社招流程”“投递简历后会怎样” → RECRUITMENT
                   - “VPN 连接”“企业邮箱”“打印机连不上网” → IT_SUPPORT
                   - “员工手册里关于xxx的规定”“违纪怎么处理” → POLICY
                2. 用户问题可能同时涉及多个内容时，选择【对用户当前核心问题最重要】的那个意图作为主意图。
                3. 如果无法明显归类，使用 GENERAL。
                
                请从下面这些意图中选择一个作为主意图（intent）：
                - RECRUITMENT: 招聘、校招、社招、面试流程等
                - COMPENSATION: 薪资、年终奖、福利、调薪、绩效等
                - ATTENDANCE: 工作时间、上班时间、请假、考勤、加班、调休等
                - POLICY: 一般公司制度、规章、行为规范等
                - IT_SUPPORT: VPN、邮箱、打印机、网络、账号密码等 IT 使用问题
                - GENERAL: 无法归类或属于以上多种的综合问题
                
                同时，你需要给出：
                - 对应的推荐向量集合（collectionName），例如：
                  - 大部分人事/制度类问题：rag_hr_collection
                  - IT 支持类问题：rag_it_collection
                - 可选的元数据过滤条件（filterExpr），用于 Milvus expr，
                  例如：biz_type in ["ATTENDANCE","WORKING_HOURS"]
                
                【输出格式要求（非常重要）】：
                1. 只输出一个 JSON 对象，不要包含任何多余文字。
                2. JSON 字段包括：
                   - intent: 字符串，取值为 ["RECRUITMENT","COMPENSATION","ATTENDANCE","POLICY","IT_SUPPORT","GENERAL"] 之一
                   - collectionName: 字符串，向量集合名称，如 "rag_hr_collection" 或 "rag_it_collection"
                   - filterExpr: 字符串或 null，Milvus 的 expr 过滤条件，如 "biz_type in [\\"ATTENDANCE\\"]"
                   - confidence: 0 到 1 之间的小数，表示你对该分类的信心度
                   - reason: 字符串，简要说明你这样分类的理由
                
                示例输出：
                {
                  "intent": "ATTENDANCE",
                  "collectionName": "rag_hr_collection",
                  "filterExpr": "biz_type in [\\"ATTENDANCE\\",\\"WORKING_HOURS\\"]",
                  "confidence": 0.92,
                  "reason": "问题询问工作时间和加班制度，属于考勤与工作时间相关"
                }
                
                现在开始，请根据下方【用户问题】进行判断，并严格按上述 JSON 格式输出。
                
                【用户问题】
                %s
                """.formatted(question);
    }

    @Test
    public void multiExpertIntentClassifier() throws Exception {
        // String question = "加班到凌晨，第二天可以几点到？";
        String question = "早上九点十分打卡，有什么处罚？";

        List<Category> categories = List.of(
                Category.RECRUITMENT,
                Category.COMPENSATION,
                Category.ATTENDANCE,
                Category.POLICY,
                Category.IT_SUPPORT,
                Category.GENERAL
        );

        ExecutorService executor = Executors.newFixedThreadPool(categories.size());

        long start = System.nanoTime();

        List<CompletableFuture<CategoryScore>> futures = categories.stream()
                .map(cat -> CompletableFuture.supplyAsync(
                        () -> scoreCategory(cat, question),
                        executor))
                .toList();

        // 等待全部完成
        List<CategoryScore> scores = futures.stream()
                .map(CompletableFuture::join)
                .toList();

        long end = System.nanoTime();
        executor.shutdown();

        System.out.println("Question: " + question);
        scores.forEach(cs -> {
            System.out.println("[" + cs.category().code() + "] score = " + cs.score()
                    + ", reason = " + cs.reason());
        });

        // 选出最高分分类
        CategoryScore best = scores.stream()
                .max(Comparator.comparingDouble(CategoryScore::score))
                .orElseThrow();

        System.out.println(">>> BEST INTENT = " + best.category().code()
                + " (score=" + best.score() + ")");
        System.out.println("Total time (parallel): " + ((end - start) / 1_000_000.0) + " ms");
    }

    private CategoryScore scoreCategory(Category category, String question) {
        String prompt = buildCategoryScorePrompt(category, question);

        // 这里用简单版 chat(String)，也可以用 ChatRequest
        String resp = llmService.chat(prompt);

        // 期望返回：
        // {"score":0.95,"reason":"问题是 Mac 打印机连接，属于 IT 支持场景"}
        try {
            JsonObject obj = gson.fromJson(resp, JsonObject.class);
            double score = obj.get("score").getAsDouble();
            String reason = obj.has("reason") && !obj.get("reason").isJsonNull()
                    ? obj.get("reason").getAsString()
                    : "";
            return new CategoryScore(category, score, reason, resp);
        } catch (Exception e) {
            log.warn("parse category score failed, category={}, resp={}", category.code(), resp, e);
            // 解析失败当 0 分
            return new CategoryScore(category, 0.0, "parse_failed", resp);
        }
    }

    private String buildCategoryScorePrompt(Category category, String question) {
        String joinedExamples = category.examples().stream()
                .map(ex -> "- " + ex)
                .collect(Collectors.joining("\n"));

        return """
                你是一个企业内部知识库 RAG 系统中的【意图评分模块】。
                现在你只负责判断：当前这个“用户问题”，和下面这个【单一分类】是否属于同一类问题。
                
                请根据分类的说明和典型问题示例，判断该用户问题与该分类的相关度，并打一个 0~1 的分数。
                
                评分说明：
                - 0.0：完全无关
                - 0.3：略有关系，但大概率不是这个分类
                - 0.6：有一定关系，可以考虑作为候选
                - 0.8~1.0：高度相关，几乎可以认为就是这个分类的问题
                
                请注意：
                - 需要支持模糊问法和同义表达（例如“上班时间”“几点打卡”“每天工作多久”，都归入考勤/工作时间类）。
                - 不需要和其他分类比较，只评估「当前分类 vs 当前问题」这一对的相关度。
                
                【当前分类】
                编码：%s
                说明：%s
                
                【该分类的典型问题示例（仅示例，并不完整）】
                %s
                
                【用户问题】
                %s
                
                【输出格式要求】：
                1. 只输出一个 JSON对象，不要包含任何多余文字。
                2. 必须包含字段：
                   - score: 0~1 的小数，例如 0.85
                   - reason: 简要中文说明你打这个分的原因
                
                示例输出：
                {
                  "score": 0.93,
                  "reason": "问题是关于 Mac 打印机连接，和 IT 支持类非常接近"
                }
                """
                .formatted(
                        category.code(),
                        category.description(),
                        joinedExamples,
                        question
                );
    }

    private enum Category {
        RECRUITMENT(
                "RECRUITMENT",
                "招聘、校招、社招、面试流程、笔试测评等相关问题",
                List.of(
                        "社招 Java 开发的面试流程是怎样的？",
                        "校招网申投递后，一般多久会出面试通知？",
                        "技术一面和二面的侧重点有什么区别？",
                        "面试通过后，多久会发 offer ？"
                )
        ),
        COMPENSATION(
                "COMPENSATION",
                "薪资、工资结构、年终奖、调薪、绩效、福利相关问题",
                List.of(
                        "公司的年终奖是怎么发的？",
                        "试用期工资和转正后一样吗？",
                        "多久会有一次调薪机会？",
                        "五险一金的缴纳比例是多少？"
                )
        ),
        ATTENDANCE(
                "ATTENDANCE",
                "工作时间、上班下班时间、加班、调休、请假、考勤打卡相关问题",
                List.of(
                        "正常的上班时间是几点到几点？",
                        "加班有没有加班费或者调休？",
                        "年假怎么申请？",
                        "忘记打卡了应该怎么办？"
                )
        ),
        POLICY(
                "POLICY",
                "公司规章制度、员工手册、违纪处理、行为规范等综合制度类问题",
                List.of(
                        "试用期内的离职需要提前多久申请？",
                        "公司对保密义务有什么要求？",
                        "员工违反纪律会怎么处理？",
                        "离职流程是怎样的？"
                )
        ),
        IT_SUPPORT(
                "IT_SUPPORT",
                "VPN、企业邮箱、办公软件、网络、打印机、电脑账号密码等 IT 使用问题",
                List.of(
                        "Mac 电脑怎么连接公司打印机？",
                        "企业邮箱在手机上怎么配置？",
                        "公司 VPN 连不上怎么办？",
                        "Windows 电脑开不了机应该找谁？"
                )
        ),
        GENERAL(
                "GENERAL",
                "无法明显归类到以上任何一类、或者是非常宽泛的综合问题",
                List.of(
                        "公司大致是做什么业务的？",
                        "能介绍一下公司的整体情况吗？",
                        "新人入职有什么需要注意的吗？"
                )
        );

        private final String code;
        private final String description;
        private final List<String> examples;

        Category(String code, String description, List<String> examples) {
            this.code = code;
            this.description = description;
            this.examples = examples;
        }

        public String code() {
            return code;
        }

        public String description() {
            return description;
        }

        public List<String> examples() {
            return examples;
        }
    }

    /**
     * 单个分类打分结果
     */
    private record CategoryScore(
            Category category,
            double score,
            String reason,
            String rawResp
    ) {
    }
}
