package com.govagent.chat;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话服务（内存实现，接口语义与 DB 版一致）。
 */
@Service
public class ChatSessionService {

    private final Map<String, ChatSession> sessions = new ConcurrentHashMap<>();

    public ChatSession create(String username) {
        ChatSession session = ChatSession.create(username);
        sessions.put(session.getId(), session);
        return session;
    }

    public Optional<ChatSession> find(String sessionId) {
        return sessionId == null ? Optional.empty() : Optional.ofNullable(sessions.get(sessionId));
    }

    public ChatSession require(String sessionId) {
        return find(sessionId).orElseThrow(() ->
                new IllegalArgumentException("会话不存在: " + sessionId));
    }

    public List<ChatSession> listByUser(String username) {
        return sessions.values().stream()
                .filter(s -> s.getUsername().equals(username))
                .sorted(Comparator.comparing(ChatSession::getUpdatedAt).reversed())
                .toList();
    }

    public void append(ChatSession session, ChatMessageRecord record) {
        session.append(record);
    }

    /** 消息反馈（点赞/点踩） */
    public boolean feedback(String messageId, int score) {
        return sessions.values().stream()
                .flatMap(s -> s.getMessages().stream())
                .filter(m -> m.getId().equals(messageId))
                .findFirst()
                .map(m -> {
                    m.setFeedback(score);
                    return true;
                })
                .orElse(false);
    }

    /** 供诊断 agent 查询写操作回执的辅助方法 */
    public List<ChatMessageRecord> toolRecordsOf(String sessionId) {
        return find(sessionId).map(s -> s.getMessages().stream()
                        .filter(m -> "tool".equals(m.getRole())).toList())
                .orElse(List.of());
    }

    public void touch(ChatSession session) {
        session.setUpdatedAt(Instant.now());
    }
}
