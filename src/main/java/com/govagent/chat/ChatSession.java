package com.govagent.chat;

import lombok.Data;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 聊天会话（内存实现）。
 */
@Data
public class ChatSession {

    private String id;
    private String username;
    private String title;
    /** 最近一次命中的 agent */
    private String lastAgentId;
    private Instant createdAt;
    private Instant updatedAt;
    private final List<ChatMessageRecord> messages = new CopyOnWriteArrayList<>();

    public static ChatSession create(String username) {
        ChatSession session = new ChatSession();
        session.setId(UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        session.setUsername(username);
        session.setCreatedAt(Instant.now());
        session.setUpdatedAt(Instant.now());
        return session;
    }

    public void append(ChatMessageRecord record) {
        messages.add(record);
        updatedAt = Instant.now();
        if (title == null && "user".equals(record.getRole())) {
            title = record.getContent() != null && record.getContent().length() > 30
                    ? record.getContent().substring(0, 30) + "…"
                    : record.getContent();
        }
    }

    public List<ChatMessageRecord> recentMessages() {
        return new ArrayList<>(messages);
    }
}
