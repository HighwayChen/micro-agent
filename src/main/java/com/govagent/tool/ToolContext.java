package com.govagent.tool;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.UUID;

/**
 * tool 执行上下文：当前用户/角色/会话，由对话链路注入，不暴露给 LLM。
 */
@Data
@Builder
public class ToolContext {

    private String username;

    private List<String> roles;

    private String sessionId;

    private String traceId;

    public static ToolContext of(String username, List<String> roles, String sessionId) {
        return ToolContext.builder()
                .username(username)
                .roles(roles)
                .sessionId(sessionId)
                .traceId(UUID.randomUUID().toString().substring(0, 8))
                .build();
    }

    public boolean hasAnyRole(List<String> expected) {
        if (roles == null || expected == null) {
            return false;
        }
        return roles.stream().anyMatch(expected::contains);
    }
}
