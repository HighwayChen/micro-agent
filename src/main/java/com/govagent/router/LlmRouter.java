package com.govagent.router;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.govagent.agent.AgentDefinition;
import com.govagent.agent.AgentRegistry;
import com.govagent.llm.OpenAiClient;
import com.govagent.llm.model.ChatCompletionRequest;
import com.govagent.llm.model.ChatMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * LLM 意图路由：把各 Agent 的 description 作为路由依据交给 LLM 判断。
 * 输出三种结果：命中 agentId / 澄清反问 / 兜底。
 * 新增 Agent 无需改本类（description 自动进入路由提示词）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LlmRouter {

    private final OpenAiClient openAiClient;
    private final AgentRegistry agentRegistry;
    private final ObjectMapper objectMapper;

    public RouteResult route(String userMessage, String recentContext) {
        String systemPrompt = buildRouterPrompt();
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(systemPrompt));
        if (recentContext != null && !recentContext.isBlank()) {
            messages.add(ChatMessage.user("（最近对话上下文，供判断指代用）\n" + recentContext));
        }
        messages.add(ChatMessage.user(userMessage));

        try {
            ChatCompletionRequest request = ChatCompletionRequest.of(
                    openAiClient.routerModel(), messages, null);
            request.setTemperature(0.0);
            request.setMaxTokens(200);
            ChatMessage reply = openAiClient.chat(request).firstMessage();
            return parse(reply == null ? "" : reply.getContent());
        } catch (Exception e) {
            log.warn("路由失败，走兜底 agent: {}", e.getMessage());
            return RouteResult.fallback();
        }
    }

    private String buildRouterPrompt() {
        StringBuilder sb = new StringBuilder();
        sb.append("""
                你是服务治理平台聊天机器人的意图路由器。根据用户问题，判断应该交给哪个领域 Agent 处理。

                可选 Agent：
                """);
        for (AgentDefinition agent : agentRegistry.all()) {
            if (AgentRegistry.GENERAL.equals(agent.getId())) {
                continue;
            }
            sb.append("- ").append(agent.getId()).append("（").append(agent.getName()).append("）：")
                    .append(agent.getDescription()).append("\n");
        }
        sb.append("""

                只输出一个 JSON 对象，不要输出其他内容：
                1. 问题明确属于某个 Agent：{"agentId": "xxx"}
                2. 问题有歧义、缺少关键信息（如没说是哪个服务）：{"clarify": "向用户确认的一句话"}
                3. 寒暄/闲聊/与平台无关：{"fallback": true}
                """);
        return sb.toString();
    }

    RouteResult parse(String content) {
        String json = extractJson(content);
        if (json == null) {
            return RouteResult.fallback();
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            if (node.has("agentId") && !node.get("agentId").asText().isBlank()) {
                String agentId = node.get("agentId").asText();
                // 防幻觉：agentId 必须真实存在
                try {
                    agentRegistry.get(agentId);
                    return RouteResult.agent(agentId);
                } catch (IllegalArgumentException e) {
                    log.warn("路由到不存在的 agent: {}", agentId);
                }
            }
            if (node.has("clarify") && !node.get("clarify").asText().isBlank()) {
                return RouteResult.clarify(node.get("clarify").asText());
            }
        } catch (Exception ignored) {
        }
        return RouteResult.fallback();
    }

    /** LLM 可能输出 ```json 包裹或附带文字，提取首个 {} 块 */
    private String extractJson(String content) {
        if (content == null) {
            return null;
        }
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        return start >= 0 && end > start ? content.substring(start, end + 1) : null;
    }
}
