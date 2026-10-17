package com.govagent.agent;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 领域 Agent 定义：路由描述 + system prompt + tool 子集。
 */
@Data
@Builder
public class AgentDefinition {

    private String id;
    private String name;
    /** 给路由 LLM 看的能力描述 */
    private String description;
    private String systemPrompt;
    /** 该 agent 可用的 tool 名称列表 */
    private List<String> tools;
}
