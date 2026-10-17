package com.govagent.conversation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.govagent.chat.ChatMessageRecord;
import com.govagent.config.AgentProperties;
import com.govagent.llm.model.ChatMessage;
import com.govagent.llm.model.ToolCall;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 多轮上下文裁剪：保留最近 N 轮（一轮 = 用户消息到下一个用户消息前的所有 assistant/tool 记录），
 * tool 结果按上限截断，控制 token 开销。
 */
@Component
@RequiredArgsConstructor
public class ContextTrimmer {

    private final ObjectMapper objectMapper;
    private final AgentProperties properties;

    /**
     * @param systemPrompt 当前 agent 的 system prompt
     * @param history      会话全部历史记录
     * @return 可直接拼进 completions 请求的消息列表
     */
    public List<ChatMessage> build(String systemPrompt, List<ChatMessageRecord> history) {
        List<List<ChatMessageRecord>> rounds = splitRounds(history);
        int keep = Math.min(rounds.size(), properties.getHistoryRounds());
        List<List<ChatMessageRecord>> kept = rounds.subList(rounds.size() - keep, rounds.size());

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(systemPrompt));
        for (List<ChatMessageRecord> round : kept) {
            for (ChatMessageRecord record : round) {
                toLlmMessage(record).ifPresent(messages::add);
            }
        }
        return messages;
    }

    private List<List<ChatMessageRecord>> splitRounds(List<ChatMessageRecord> history) {
        List<List<ChatMessageRecord>> rounds = new ArrayList<>();
        List<ChatMessageRecord> current = new ArrayList<>();
        for (ChatMessageRecord record : history) {
            if ("user".equals(record.getRole()) && !current.isEmpty()) {
                rounds.add(current);
                current = new ArrayList<>();
            }
            current.add(record);
        }
        if (!current.isEmpty()) {
            rounds.add(current);
        }
        return rounds;
    }

    private java.util.Optional<ChatMessage> toLlmMessage(ChatMessageRecord record) {
        return switch (record.getRole()) {
            case "user" -> java.util.Optional.of(ChatMessage.user(record.getContent()));
            case "assistant" -> {
                List<ToolCall> toolCalls = parseToolCalls(record.getToolCallsJson());
                yield java.util.Optional.of(ChatMessage.builder()
                        .role("assistant")
                        .content(record.getContent() == null || record.getContent().isBlank()
                                ? null : record.getContent())
                        .toolCalls(toolCalls.isEmpty() ? null : toolCalls)
                        .build());
            }
            case "tool" -> java.util.Optional.of(ChatMessage.tool(
                    record.getToolCallId(), record.getToolName(), truncate(record.getContent())));
            default -> java.util.Optional.empty();
        };
    }

    private List<ToolCall> parseToolCalls(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<ToolCall>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private String truncate(String content) {
        if (content == null) {
            return "";
        }
        int max = properties.getResultMaxBytes();
        return content.getBytes(StandardCharsets.UTF_8).length <= max
                ? content
                : content.substring(0, Math.min(content.length(), max)) + "...(已截断)";
    }
}
