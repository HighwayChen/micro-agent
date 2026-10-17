package com.govagent.llm;

import com.govagent.llm.model.ChatMessage;
import com.govagent.llm.model.StreamChunk;
import com.govagent.llm.model.ToolCall;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 流式响应聚合器。
 * <p>
 * 处理 OpenAI 流式协议的两个难点：
 * 1. 文本按 token 分片到达，逐片吐出（每片即一个 SSE token 事件）
 * 2. tool_calls 按 index 分片到达：首片带 id + function.name，后续片只有同 index 的
 *    function.arguments 片段，必须按 index 聚合并拼接 arguments，等 finish_reason=tool_calls
 *    时才能得到完整的调用参数
 */
public class StreamAggregator {

    private final StringBuilder content = new StringBuilder();
    private final Map<Integer, ToolCallBuilder> toolCalls = new LinkedHashMap<>();
    private String finishReason;

    /**
     * 喂入一个 chunk。
     *
     * @return 新增的文本分片；无文本分片时返回 null
     */
    public String feed(StreamChunk chunk) {
        if (chunk.getChoices() == null || chunk.getChoices().isEmpty()) {
            return null;
        }
        StreamChunk.Choice choice = chunk.getChoices().get(0);
        if (choice.getFinishReason() != null) {
            this.finishReason = choice.getFinishReason();
        }
        StreamChunk.Delta delta = choice.getDelta();
        if (delta == null) {
            return null;
        }
        if (delta.getToolCalls() != null) {
            for (StreamChunk.ToolCallDelta tc : delta.getToolCalls()) {
                mergeToolCall(tc);
            }
        }
        if (delta.getContent() != null && !delta.getContent().isEmpty()) {
            content.append(delta.getContent());
            return delta.getContent();
        }
        return null;
    }

    private void mergeToolCall(StreamChunk.ToolCallDelta tc) {
        int index = tc.getIndex() == null ? 0 : tc.getIndex();
        ToolCallBuilder builder = toolCalls.computeIfAbsent(index, k -> new ToolCallBuilder());
        if (tc.getId() != null) {
            builder.id = tc.getId();
        }
        if (tc.getFunction() != null) {
            if (tc.getFunction().getName() != null) {
                builder.name = tc.getFunction().getName();
            }
            if (tc.getFunction().getArguments() != null) {
                builder.arguments.append(tc.getFunction().getArguments());
            }
        }
    }

    /** 本轮是否以 tool 调用结束（需要执行 tool 并再次请求 LLM） */
    public boolean wantsToolCall() {
        return "tool_calls".equals(finishReason);
    }

    public String getFinishReason() {
        return finishReason;
    }

    /** 聚合出本轮的 assistant 消息（含完整 toolCalls），用于回填对话历史 */
    public ChatMessage buildAssistantMessage() {
        List<ToolCall> calls = new ArrayList<>();
        for (ToolCallBuilder builder : toolCalls.values()) {
            calls.add(ToolCall.builder()
                    .id(builder.id)
                    .type("function")
                    .function(ToolCall.FunctionCall.builder()
                            .name(builder.name)
                            .arguments(builder.arguments.toString())
                            .build())
                    .build());
        }
        String text = content.isEmpty() ? null : content.toString();
        return ChatMessage.builder()
                .role("assistant")
                .content(text)
                .toolCalls(calls.isEmpty() ? null : calls)
                .build();
    }

    public String getContent() {
        return content.toString();
    }

    private static class ToolCallBuilder {
        String id;
        String name;
        StringBuilder arguments = new StringBuilder();
    }
}
