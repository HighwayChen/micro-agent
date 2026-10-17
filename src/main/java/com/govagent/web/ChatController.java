package com.govagent.web;

import com.govagent.chat.AgentChatService;
import com.govagent.web.dto.ChatRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 聊天入口（SSE 流式）。
 * <p>
 * demo 单用户模式：可通过 X-Username 头指定用户名（默认 demo），角色默认 admin。
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ChatController {

    private final AgentChatService agentChatService;

    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestBody @Valid ChatRequest request,
                           @RequestHeader(value = "X-Username", defaultValue = "demo") String username) {
        return agentChatService.chat(request.getSessionId(), request.getMessage(), username);
    }
}
