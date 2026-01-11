package com.nageoffer.ai.ragent.rag.prompt;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.rag.intent.IntentNode;
import com.nageoffer.ai.ragent.rag.intent.NodeScore;
import com.nageoffer.ai.ragent.rag.retrieve.RetrievedChunk;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Service
public class RAGStandardPromptService {

    /**
     * 默认 RAG 问答提示词模板
     * 用于指导大模型基于检索到的文档内容进行准确回答，包含严格的事实性约束和链接处理规则。
     * 模板通过两个 {@code %s} 占位符分别接收文档内容和用户问题。
     * <p>
     * 主模板（最后两个 %s 依次为 文档内容 / 用户问题）
     */
    public static final String RAG_DEFAULT_PROMPT = """
            你是专业的企业内 RAG 问答助手，只能基于【文档内容】进行回答，不能使用外部知识或网络搜索。
            
            【重要限制】
            1. 你无法访问互联网，不能打开任何链接或下载文件。
            2. 对于文档中的链接（如 [标题](URL)）和图片（如 ![描述](URL) 或 <img> 标签），你只能看到“标题/描述文本 + URL”，看不到其中真实内容。
            3. 绝对禁止根据链接标题、URL、图片描述或你对类似文档的常识，去推断、补全或想象链接/图片内部的具体内容。
            4. 只要某个具体事实、条款、架构、流程、数字等没有在【文档内容】中以文字形式出现，就视为知识库未收录该信息，不得写入回答。
            
            【文档内容含义】
            1. 【文档内容】表示“当前知识库检索到的相关片段”，只包含你现在能看到的文字信息，以及其中出现的链接标题和 URL。
            2. 其中的“在线地址”“链接列表”等，只说明知识库中记录了这些外部资源的入口，你看不到这些链接内部的每一行数据或完整正文。
            
            【链接与图片处理规则】
            1. 当用户的问题与某个链接直接相关（例如询问某白皮书、某 PPT、某在线表格、某文档的详细内容或核心要点）时：
               - 如果【文档内容】中只有该链接的名称/简短说明 + URL，没有展开正文内容：
                 - 需要在回答中说明：当前知识库仅提供了该资料的名称和访问链接，未提供更详细的正文内容，因此无法基于现有信息总结具体内容。
                 - 同时，需要原样返回与该问题相关的链接列表（保留 Markdown 格式或“标题 + URL”的形式），方便用户自行点击查看。
               - 如果【文档内容】中既有简要文字说明，又有链接：
                 - 可以先基于这段文字做简要概括，再附上相关链接列表，同样不能推断链接内部未展示的内容。
            2. 当用户的问题较为宽泛，而【文档内容】主要是一份“链接汇总”或“资源导航”时：
               - 可以回答为：知识库中收录了与该主题相关的若干资料，并列出相关条目的标题和链接。
               - 不得假装已经阅读这些链接内部的完整内容。
            3. 只要【文档内容】中出现了与用户问题相关的链接（如 `[标题](URL)` 或类似形式），在回答中**必须至少出现一次完整的链接**：
               - 不允许只提资源名称而省略 URL；
               - 不允许把带链接的标题改写成没有 URL 的普通文本。
            4. 【图片输出的严格要求】：
               - 当你在【文档内容】中看到以 Markdown 图片语法 `![描述](URL)` 出现的内容时，如果你在回答中需要引用这张图片，必须至少出现一次**完全相同、未改动**的 `![描述](URL)` 片段；
               - 不允许把 `![描述](URL)` 改写成 `[描述](URL)` 或其他形式，也不允许省略开头的感叹号 `!`；
               - 若原始内容本身就是普通链接 `[标题](URL)`，则按普通链接规则处理，**不要主动为其添加或删除开头的感叹号**。
            
            【回答规则】
            1. 回答必须严格基于【文档内容】中已经展示出来的文字信息。
            2. 不得虚构信息，不得补充知识库中没有明文出现的内容。
            3. 回答可以适度丰富表达（例如分点说明、对【文档内容】已有内容做简要解释），但不得引入知识库中不存在的新事实。
            4. 若【文档内容】未包含与用户问题明显相关的任何信息，且也没有相关链接可引用，可以说明当前知识库暂未收录相关内容，避免无中生有。
            5. 若【文档内容】仅提供了与问题相关的链接或资源名称，请说明当前只能提供这些链接信息，并列出这些链接。
            
            【对外表述约束】
            1. 面向用户回答时，默认不要使用“文档未包含……”“上述文档”“上述【文档内容】”等说法来指代知识来源。
               - 除非用户在问题中明确提到“某个文档”“这份文档”，否则不要主动使用“文档未包含……”这类句式。
            2. 当需要说明当前信息范围有限或没有命中某个对象时，优先使用“知识库 / 当前能查到的信息”的视角，推荐类似表达：
               - “目前在知识库中，没有检索到关于『XXX』的明确记录。”
               - “从当前能够查到的信息来看，未看到对『XXX』的直接说明。”
               - “现在收录的只是若干相关资料和链接，文字部分没有单独提到『XXX』的具体情况，因此无法判断其状态。”
            3. 当你判断需要触发兜底时，你的回答必须只包含下面这一句话：未检索到与问题相关的文档内容。
            4. 严禁出现以下句式：
               - “文档未包含 XXX”
               - “上述【文档内容】未包含 XXX”
               - “当前文档中没有 XXX 的信息”
               如需表达类似含义，必须改写为“目前在知识库中尚未检索到关于 XXX 的明确记录”等。
            
            【表达与去重规则】
            1. 同一事实或结论只需要说明一次，不要用不同句式反复重复同一个意思。
            2. 可以在结尾用一句简短总结进行收束，但不要机械拷贝前文句子。
            3. 若采用分点说明，每一个要点应尽量提供新的信息或不同角度，而不是对上一条的简单改写。
            
            【输出风格】
            1. 回答可以适度分点，条理清晰即可。
            2. 不要引用你自己“知道的”外部知识，只能复述和组织【文档内容】中已经出现的信息。
            3. 遇到只含链接的场景，务必在回答中包含对应的链接。
            
            【文档内容】
            %s
            
            【用户问题】
            %s
            """;

    /**
     * 允许 2+ 个连续换行被压成 2 个，成品更干净
     */
    private static final Pattern MULTI_BLANK_LINES = Pattern.compile("(\\n){3,}");

    public String buildPrompt(String docContent, String userQuestion, String baseTemplate) {
        String tpl = StrUtil.isNotBlank(baseTemplate) ? baseTemplate : RAG_DEFAULT_PROMPT;

        String prompt = tpl.formatted(
                defaultString(docContent).trim(),
                defaultString(userQuestion).trim()
        );

        prompt = MULTI_BLANK_LINES.matcher(prompt).replaceAll("\n\n").trim();
        return prompt;
    }

    public PromptPlan planPrompt(List<NodeScore> intents, Map<String, List<RetrievedChunk>> intentChunks) {
        List<NodeScore> safeIntents = intents == null ? Collections.emptyList() : intents;

        // 1) 先剔除“未命中检索”的意图
        List<NodeScore> retained = safeIntents.stream()
                .filter(ns -> {
                    IntentNode node = ns.getNode();
                    String key = nodeKey(node);
                    List<RetrievedChunk> chunks = intentChunks == null ? null : intentChunks.get(key);
                    return CollUtil.isNotEmpty(chunks);
                })
                .toList();

        if (retained.isEmpty()) {
            // 没有任何可用意图：无基模板（上层可根据业务选择 fallback）
            return new PromptPlan(Collections.emptyList(), null);
        }

        // 2) 单 / 多意图的模板与片段策略
        if (retained.size() == 1) {
            IntentNode only = retained.get(0).getNode();
            String tpl = StrUtil.emptyIfNull(only.getPromptTemplate()).trim();

            if (StrUtil.isNotBlank(tpl)) {
                // 单意图 + 有模板：使用模板本身
                return new PromptPlan(retained, tpl);
            } else {
                // 单意图 + 无模板：走默认模板，snippet 作为补充规则
                return new PromptPlan(retained, null);
            }
        } else {
            // 多意图：统一默认模板，合并所有片段（去空/去重）
            return new PromptPlan(retained, null);
        }
    }

    public String buildPrompt(String docContent, String userQuestion,
                              List<NodeScore> intents, Map<String, List<RetrievedChunk>> intentChunks) {
        PromptPlan plan = planPrompt(intents, intentChunks);
        return buildPrompt(docContent, userQuestion, plan.getBaseTemplate());
    }

    // === 工具方法 ===

    /**
     * 统一从 IntentNode 取 key（优先 intentCode，退化为 id）
     */
    private static String nodeKey(IntentNode node) {
        if (node == null) return "";
        if (StrUtil.isNotBlank(node.getId())) return node.getId();
        return String.valueOf(node.getId());
    }

    private static String defaultString(String s) {
        return s == null ? "" : s;
    }
}
