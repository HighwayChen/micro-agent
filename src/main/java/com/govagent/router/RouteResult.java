package com.govagent.router;

import lombok.Data;

/**
 * 路由结果三分支：命中 agent / 需要澄清（附澄清话术）/ 兜底。
 */
@Data
public class RouteResult {

    public enum Type { AGENT, CLARIFY, FALLBACK }

    private final Type type;
    private final String agentId;
    private final String clarifyQuestion;

    public static RouteResult agent(String agentId) {
        return new RouteResult(Type.AGENT, agentId, null);
    }

    public static RouteResult clarify(String question) {
        return new RouteResult(Type.CLARIFY, null, question);
    }

    public static RouteResult fallback() {
        return new RouteResult(Type.FALLBACK, null, null);
    }
}
