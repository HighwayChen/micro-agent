package com.govagent.chat;

import lombok.Data;

import java.time.Instant;
import java.util.UUID;

/**
 * 会话内的一条消息记录（含 tool 调用记录），内存存储。
 */
@Data
public class ChatMessageRecord {

    public static final int MAX_STORED_TOOL_BYTES = 2048;

    private String id;
    private String sessionId;
    /** user / assistant / tool */
    private String role;
    private String content;
    /** role=tool 时：tool 名称 */
    private String toolName;
    /** role=tool 时：对应 toolCallId */
    private String toolCallId;
    /** role=assistant 时：LLM 发起的 tool 调用 JSON */
    private String toolCallsJson;
    /** 反馈：0 无 / 1 有用 / -1 无用 */
    private int feedback;
    private Instant createdAt;

    public static ChatMessageRecord user(String sessionId, String content) {
        ChatMessageRecord record = base(sessionId, "user");
        record.setContent(content);
        return record;
    }

    public static ChatMessageRecord assistant(String sessionId, String content, String toolCallsJson) {
        ChatMessageRecord record = base(sessionId, "assistant");
        record.setContent(content);
        record.setToolCallsJson(toolCallsJson);
        return record;
    }

    public static ChatMessageRecord tool(String sessionId, String toolName, String toolCallId, String content) {
        ChatMessageRecord record = base(sessionId, "tool");
        record.setToolName(toolName);
        record.setToolCallId(toolCallId);
        record.setContent(truncate(content));
        return record;
    }

    private static ChatMessageRecord base(String sessionId, String role) {
        ChatMessageRecord record = new ChatMessageRecord();
        record.setId(UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        record.setSessionId(sessionId);
        record.setRole(role);
        record.setCreatedAt(Instant.now());
        return record;
    }

    private static String truncate(String content) {
        return content != null && content.length() > MAX_STORED_TOOL_BYTES
                ? content.substring(0, MAX_STORED_TOOL_BYTES) + "...(截断)"
                : content;
    }
}
