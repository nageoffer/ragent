package com.nageoffer.ai.ragent.core.convention;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 通用大模型请求对象
 *
 * <p>
 * 用于封装一次完整对话所需的所有上下文与控制参数，作为「统一入参」传给
 * 各种不同厂商 / 协议的大模型接口（如 Ollama、百炼、OpenAI 等），
 * 方便在适配层做统一转换
 * </p>
 *
 * <p>典型使用方式：</p>
 * <pre>
 * ChatRequest req = ChatRequest.builder()
 *     .prompt("帮我总结下这段文本")
 *     .systemPrompt("你是一个企业内部知识库助手")
 *     .context(ragContext)          // 可选：RAG 召回的文档内容
 *     .history(historyMessages)     // 可选：对话历史
 *     .temperature(0.3)
 *     .maxTokens(512)
 *     .build();
 * </pre>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChatRequest {

    /**
     * 当前用户输入内容（通常就是自然语言问题或指令，比如 query）
     * <p>
     * 对应大多数厂商接口中的 {@code prompt} 或 {@code input} 字段
     * </p>
     */
    private String prompt;

    /**
     * 可选：系统提示词（System Prompt）
     * <p>
     * 用于设定模型的整体角色与行为规范，例如：
     * {@code "你是一个企业知识库助手，回答时重点引用文档内容"}
     * 一般会在适配层转换为模型的 system 角色消息
     * </p>
     */
    private String systemPrompt;

    /**
     * 对话历史消息列表
     * <p>
     * 一般包含用户（user）和助手（assistant）的多轮对话记录，
     * 用于让大模型理解上下文，生成连贯的回复
     * </p>
     */
    private List<ChatMessage> history = new ArrayList<>();

    /**
     * 可选：RAG 召回的上下文内容
     * <p>
     * 通常为从向量库 / 检索系统中召回的文档片段，供大模型参考
     * 可以在实现层决定如何注入，例如：
     * <ul>
     *   <li>拼接到 {@link #systemPrompt} 前部，作为「知识背景」</li>
     *   <li>作为单独一条 system/user 消息插入到消息列表</li>
     * </ul>
     * </p>
     */
    private String context;

    // ================== 模型控制参数 ==================

    /**
     * 采样温度参数，取值通常为 0～2
     * <p>
     * 数值越小，输出越稳定、保守；数值越大，生成内容越发散、创造性更强
     * 例如：问答场景可用 0.1～0.5，创作类可用 0.7 以上
     * </p>
     */
    private Double temperature;

    /**
     * nucleus sampling（Top-P）参数
     * <p>
     * 表示从累积概率为 P 的词集合中采样，常与 {@link #temperature} 搭配使用
     * 一般取值在 0.8～0.95 之间，越小越保守
     * 若为 {@code null} 则使用模型默认值
     * </p>
     */
    private Double topP;

    /**
     * 限制模型本次回答最多生成的 token 数量
     * <p>
     * 可用于控制回复长度与成本；若为 {@code null}，则走模型或服务端默认配置
     * </p>
     */
    private Integer maxTokens;

    /**
     * 可选：是否启用「思考模式」开关
     * <p>
     * 占坑字段，用于兼容支持思考过程 / reasoning 扩展能力的模型，
     * 具体含义由对接的大模型服务决定（例如是否返回中间推理过程等）
     * 不支持该能力的实现可以忽略该字段
     * </p>
     */
    private Boolean thinking;

    /**
     * 可选：是否启用工具调用（Tool Calling / Function Calling）
     * <p>
     * 当前预留字段，方便后续扩展为带工具调用能力的对话请求：
     * <ul>
     *   <li>{@code false}：只进行纯文本对话</li>
     *   <li>{@code true}：允许模型按照定义调用工具 / 函数</li>
     * </ul>
     * 具体工具列表、调用结果处理由上层或实现层定义
     * </p>
     */
    private boolean enableTools;
}
