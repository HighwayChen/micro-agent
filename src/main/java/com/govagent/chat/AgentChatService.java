package com.govagent.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.govagent.agent.AgentDefinition;
import com.govagent.agent.AgentRegistry;
import com.govagent.config.AgentProperties;
import com.govagent.config.OpenAiProperties;
import com.govagent.conversation.ContextTrimmer;
import com.govagent.llm.OpenAiClient;
import com.govagent.llm.StreamAggregator;
import com.govagent.llm.model.ChatCompletionRequest;
import com.govagent.llm.model.ChatMessage;
import com.govagent.llm.model.StreamChunk;
import com.govagent.llm.model.ToolCall;
import com.govagent.llm.model.ToolDefinition;
import com.govagent.router.LlmRouter;
import com.govagent.router.RouteResult;
import com.govagent.sse.ChatEvent;
import com.govagent.tool.ToolContext;
import com.govagent.tool.ToolExecutionResult;
import com.govagent.tool.ToolExecutor;
import com.govagent.tool.ToolRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 对话主流程编排：路由 → Agent ReAct 循环（LLM ⇄ tool）→ SSE 事件流。
 * <pre>
 * 用户消息 → LlmRouter（意图路由/澄清/兜底）
 *          → Agent（system prompt + tool 子集）
 *          → 循环：流式 completions → token 事件
 *                  finish_reason=tool_calls → 执行/拦截 tool → 结果回填 → 再次 completions
 *          → done 事件
 * </pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentChatService {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final OpenAiClient openAiClient;
    private final OpenAiProperties openAiProperties;
    private final AgentProperties agentProperties;
    private final AgentRegistry agentRegistry;
    private final LlmRouter llmRouter;
    private final ToolRegistry toolRegistry;
    private final ToolExecutor toolExecutor;
    private final ChatSessionService sessionService;
    private final ContextTrimmer contextTrimmer;
    private final ObjectMapper objectMapper;

    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "agent-chat");
        thread.setDaemon(true);
        return thread;
    });

    public SseEmitter chat(String sessionId, String message, String username) {
        SseEmitter emitter = new SseEmitter(600_000L);
        ChatSession session = sessionService.find(sessionId)
                .orElseGet(() -> sessionService.create(username));
        ToolContext ctx = ToolContext.of(username, List.of("admin"), session.getId());
        sessionService.append(session, ChatMessageRecord.user(session.getId(), message));

        executor.execute(() -> {
            try {
                if (!openAiProperties.isEnabled()) {
                    send(emitter, ChatEvent.of(ChatEvent.ERROR)
                            .setMessage("LLM 未启用（agent.llm.enabled=false），请配置 AGENT_LLM_BASE_URL / AGENT_LLM_API_KEY 后重试"));
                    emitter.complete();
                    return;
                }
                runLoop(session, message, ctx, emitter);
                emitter.complete();
            } catch (Exception e) {
                log.error("对话执行异常 session={}", session.getId(), e);
                try {
                    send(emitter, ChatEvent.of(ChatEvent.ERROR).setMessage("执行异常: " + e.getMessage()));
                    emitter.complete();
                } catch (Exception ignored) {
                }
            }
        });
        return emitter;
    }

    private void runLoop(ChatSession session, String userMessage, ToolContext ctx, SseEmitter emitter) {
        RouteResult route = llmRouter.route(userMessage, recentContext(session));

        // 澄清：直接反问，不进入 agent 循环
        if (route.getType() == RouteResult.Type.CLARIFY) {
            ChatEvent start = ChatEvent.of(ChatEvent.MESSAGE_START)
                    .setAgentId("router").setAgentName("意图澄清");
            send(emitter, start);
            send(emitter, ChatEvent.of(ChatEvent.TOKEN).setDelta(route.getClarifyQuestion()));
            ChatMessageRecord record = ChatMessageRecord.assistant(session.getId(), route.getClarifyQuestion(), null);
            sessionService.append(session, record);
            send(emitter, doneEvent(record.getId(), "clarify", 0));
            return;
        }

        String agentId = route.getType() == RouteResult.Type.AGENT
                ? route.getAgentId() : AgentRegistry.GENERAL;
        AgentDefinition agent = agentRegistry.get(agentId);
        session.setLastAgentId(agentId);
        send(emitter, ChatEvent.of(ChatEvent.MESSAGE_START)
                .setAgentId(agent.getId()).setAgentName(agent.getName()));

        String systemPrompt = agent.getSystemPrompt()
                + "\n\n当前时间：" + TIME.format(LocalDateTime.now())
                + "（回答中涉及『x小时前』等相对时间时请据此换算）";

        List<ChatMessage> messages = contextTrimmer.build(systemPrompt, session.recentMessages());
        List<ToolDefinition> tools = toolRegistry.definitions(agent.getTools());

        int loops = 0;
        String finishReason = "stop";
        String lastAssistantId = null;
        while (loops < agentProperties.getMaxLoopRounds()) {
            loops++;
            StreamAggregator aggregator = new StreamAggregator();
            ChatCompletionRequest request = ChatCompletionRequest.of(
                    openAiClient.defaultModel(), messages, tools);
            try {
                for (StreamChunk chunk : openAiClient.streamChat(request).flux().toIterable()) {
                    String token = aggregator.feed(chunk);
                    if (token != null) {
                        send(emitter, ChatEvent.of(ChatEvent.TOKEN).setDelta(token));
                    }
                }
            } catch (Exception e) {
                log.error("LLM 流式调用失败 loop={}", loops, e);
                send(emitter, ChatEvent.of(ChatEvent.TOKEN)
                        .setDelta("\n\n（模型调用失败：" + e.getMessage() + "）"));
                break;
            }

            ChatMessage assistantMessage = aggregator.buildAssistantMessage();
            finishReason = aggregator.getFinishReason() == null ? "stop" : aggregator.getFinishReason();
            lastAssistantId = persistAssistant(session, assistantMessage);
            messages.add(assistantMessage);

            if (!aggregator.wantsToolCall() || assistantMessage.getToolCalls() == null) {
                break;
            }

            // 执行本轮全部 tool 调用，结果回填继续下一轮
            for (ToolCall toolCall : assistantMessage.getToolCalls()) {
                emitAndRunTool(session, ctx, emitter, messages, toolCall);
            }
        }

        if (loops >= agentProperties.getMaxLoopRounds()) {
            send(emitter, ChatEvent.of(ChatEvent.TOKEN)
                    .setDelta("\n\n（已达最大工具调用轮数 " + agentProperties.getMaxLoopRounds() + "，本轮停止）"));
        }
        send(emitter, doneEvent(lastAssistantId, finishReason, loops));
        log.info("[chat] session={} agent={} loops={} finish={}",
                session.getId(), agentId, loops, finishReason);
    }

    private void emitAndRunTool(ChatSession session, ToolContext ctx, SseEmitter emitter,
                                List<ChatMessage> messages, ToolCall toolCall) {
        String toolName = toolCall.getFunction().getName();
        String args = toolCall.getFunction().getArguments();
        send(emitter, ChatEvent.of(ChatEvent.TOOL_CALL)
                .setToolCallId(toolCall.getId())
                .setToolName(toolName)
                .setArgsPreview(preview(args, 200)));

        ToolExecutionResult result = toolExecutor.execute(toolName, args, ctx);
        switch (result.getType()) {
            case EXECUTED -> send(emitter, ChatEvent.of(ChatEvent.TOOL_RESULT)
                    .setToolCallId(toolCall.getId())
                    .setToolName(toolName)
                    .setOk(true)
                    .setCostMs(result.getCostMs())
                    .setPreview(preview(result.getResultJson(), 300)));
            case CONFIRM_REQUIRED -> send(emitter, ChatEvent.of(ChatEvent.CONFIRM_REQUIRED)
                    .setToolName(toolName)
                    .setOpId(result.getPendingOperation().getOpId())
                    .setSummary(result.getPendingOperation().getSummary())
                    .setRiskDesc(result.getPendingOperation().getRiskDesc())
                    .setExpireAt(result.getPendingOperation().getExpireAt().toString()));
            case ERROR -> send(emitter, ChatEvent.of(ChatEvent.TOOL_RESULT)
                    .setToolCallId(toolCall.getId())
                    .setToolName(toolName)
                    .setOk(false)
                    .setPreview(preview(result.getErrorMessage(), 300)));
        }

        String content = result.toToolMessageContent();
        sessionService.append(session, ChatMessageRecord.tool(
                session.getId(), toolName, toolCall.getId(), content));
        messages.add(ChatMessage.tool(toolCall.getId(), toolName, content));
    }

    private String persistAssistant(ChatSession session, ChatMessage assistantMessage) {
        String toolCallsJson = null;
        if (assistantMessage.getToolCalls() != null && !assistantMessage.getToolCalls().isEmpty()) {
            try {
                toolCallsJson = objectMapper.writeValueAsString(assistantMessage.getToolCalls());
            } catch (Exception ignored) {
            }
        }
        ChatMessageRecord record = ChatMessageRecord.assistant(session.getId(),
                assistantMessage.getContent(), toolCallsJson);
        sessionService.append(session, record);
        return record.getId();
    }

    private ChatEvent doneEvent(String messageId, String finishReason, int loops) {
        return ChatEvent.of(ChatEvent.DONE)
                .setMessageId(messageId)
                .setFinishReason(finishReason)
                .setLoops(loops);
    }

    /** 路由上下文：最近两条用户消息（解决『那个服务又挂了』的指代） */
    private String recentContext(ChatSession session) {
        List<String> userMessages = session.getMessages().stream()
                .filter(m -> "user".equals(m.getRole()))
                .map(ChatMessageRecord::getContent)
                .toList();
        if (userMessages.isEmpty()) {
            return null;
        }
        return String.join(" | ", userMessages.subList(
                Math.max(0, userMessages.size() - 2), userMessages.size() - 1));
    }

    private String preview(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    private void send(SseEmitter emitter, ChatEvent event) {
        try {
            emitter.send(SseEmitter.event()
                    .name(event.getType())
                    .data(event.toJson(objectMapper), MediaType.APPLICATION_JSON));
        } catch (Exception e) {
            log.debug("SSE 发送失败（客户端可能已断开）: {}", e.getMessage());
        }
    }
}
