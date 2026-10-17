package com.govagent.confirm;

import lombok.Data;

import java.time.Instant;

/**
 * 写操作待确认预案。由 ToolExecutor 在拦截到 WRITE tool 时生成，
 * 用户确认后由 WriteOperationExecutor 真正执行。
 */
@Data
public class PendingOperation {

    public enum Status { PENDING, EXECUTING, SUCCESS, FAILED, CANCELLED, EXPIRED }

    private String opId;
    private String toolName;
    private String argsJson;
    /** 人类可读摘要（确认卡片标题） */
    private String summary;
    private String riskDesc;
    private Status status;
    private String username;
    private String sessionId;
    private Instant createdAt;
    private Instant expireAt;
    /** 执行回执 */
    private String result;
    private String errorMessage;
    private Instant finishedAt;

    public boolean expired() {
        return Instant.now().isAfter(expireAt);
    }
}
