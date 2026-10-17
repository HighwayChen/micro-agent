package com.govagent.web;

import com.govagent.agent.AgentRegistry;
import com.govagent.chat.ChatSession;
import com.govagent.chat.ChatSessionService;
import com.govagent.web.dto.FeedbackRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 会话历史、消息反馈、Agent 清单。
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class SessionController {

    private final ChatSessionService sessionService;
    private final AgentRegistry agentRegistry;

    @GetMapping("/sessions")
    public List<Map<String, Object>> sessions(@RequestParam(defaultValue = "demo") String username) {
        return sessionService.listByUser(username).stream()
                .map(s -> Map.<String, Object>of(
                        "id", s.getId(),
                        "title", s.getTitle() == null ? "新对话" : s.getTitle(),
                        "agentId", s.getLastAgentId() == null ? "" : s.getLastAgentId(),
                        "updatedAt", s.getUpdatedAt().toString()))
                .toList();
    }

    @GetMapping("/sessions/{id}/messages")
    public List<Map<String, Object>> messages(@PathVariable String id) {
        ChatSession session = sessionService.require(id);
        return session.recentMessages().stream()
                .map(m -> Map.<String, Object>of(
                        "id", m.getId(),
                        "role", m.getRole(),
                        "content", m.getContent() == null ? "" : m.getContent(),
                        "toolName", m.getToolName() == null ? "" : m.getToolName(),
                        "toolCallId", m.getToolCallId() == null ? "" : m.getToolCallId(),
                        "toolCallsJson", m.getToolCallsJson() == null ? "" : m.getToolCallsJson(),
                        "feedback", m.getFeedback()))
                .toList();
    }

    @PostMapping("/feedback")
    public Map<String, Object> feedback(@RequestBody @Valid FeedbackRequest request) {
        boolean ok = sessionService.feedback(request.getMessageId(), request.getScore());
        return Map.of("ok", ok);
    }

    @GetMapping("/agents")
    public List<Map<String, Object>> agents() {
        return agentRegistry.all().stream()
                .map(a -> Map.<String, Object>of(
                        "id", a.getId(),
                        "name", a.getName(),
                        "description", a.getDescription(),
                        "tools", a.getTools()))
                .toList();
    }
}
