package com.govagent.tool;

import com.govagent.confirm.PendingOperation;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * tool 执行结果三分支：
 * EXECUTED        — 只读工具已执行，携带 JSON 结果
 * CONFIRM_REQUIRED — 写工具被拦截，生成待确认预案
 * ERROR           — 执行失败（异常/权限不足）
 */
@Getter
@RequiredArgsConstructor
public class ToolExecutionResult {

    public enum Type { EXECUTED, CONFIRM_REQUIRED, ERROR }

    private final Type type;
    private final String resultJson;
    private final PendingOperation pendingOperation;
    private final String errorMessage;
    private final long costMs;

    public static ToolExecutionResult executed(String json, long costMs) {
        return new ToolExecutionResult(Type.EXECUTED, json, null, null, costMs);
    }

    public static ToolExecutionResult confirmRequired(PendingOperation op) {
        return new ToolExecutionResult(Type.CONFIRM_REQUIRED, null, op, null, 0);
    }

    public static ToolExecutionResult error(String message, long costMs) {
        return new ToolExecutionResult(Type.ERROR, null, null, message, costMs);
    }

    /** 给 LLM 的回填文本（tool role message content） */
    public String toToolMessageContent() {
        return switch (type) {
            case EXECUTED -> resultJson;
            case CONFIRM_REQUIRED -> "{\"status\":\"CONFIRM_REQUIRED\",\"opId\":\"" + pendingOperation.getOpId()
                    + "\",\"summary\":\"" + pendingOperation.getSummary()
                    + "\",\"hint\":\"已生成操作预案，等待用户在聊天界面确认后执行。请告知用户需要确认。\"}";
            case ERROR -> "{\"status\":\"ERROR\",\"error\":\"" + errorMessage + "\"}";
        };
    }
}
