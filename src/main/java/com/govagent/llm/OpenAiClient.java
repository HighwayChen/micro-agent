package com.govagent.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.govagent.config.OpenAiProperties;
import com.govagent.llm.model.ChatCompletionRequest;
import com.govagent.llm.model.ChatCompletionResponse;
import com.govagent.llm.model.StreamChunk;
import io.netty.channel.ChannelOption;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

/**
 * 手写的 OpenAI 兼容协议客户端（不依赖任何 AI SDK）。
 * <p>
 * 支持任意 OpenAI 兼容网关（one-api / vLLM / DashScope / DeepSeek 等）：
 * - {@link #chat}: 非流式，用于意图路由等轻量调用
 * - {@link #streamChat}: SSE 流式 + tool calling，用于 Agent ReAct 循环
 */
@Slf4j
@Component
public class OpenAiClient {

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final OpenAiProperties properties;

    public OpenAiClient(OpenAiProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, properties.getConnectTimeoutMs())
                .responseTimeout(Duration.ofMillis(properties.getReadTimeoutMs()));
        this.webClient = WebClient.builder()
                .baseUrl(properties.getBaseUrl())
                .defaultHeader("Authorization", "Bearer " + properties.getApiKey())
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }

    /** 非流式调用（阻塞），适合路由分类等短调用 */
    public ChatCompletionResponse chat(ChatCompletionRequest request) {
        request.setStream(false);
        if (request.getTemperature() == null) {
            request.setTemperature(properties.getTemperature());
        }
        if (request.getMaxTokens() == null) {
            request.setMaxTokens(properties.getMaxTokens());
        }
        return webClient.post()
                .uri("/v1/chat/completions")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(ChatCompletionResponse.class)
                .block(Duration.ofMillis(properties.getReadTimeoutMs()));
    }

    /** 流式调用，返回解析后的 chunk 流（已过滤 [DONE]） */
    public FluxStream streamChat(ChatCompletionRequest request) {
        request.setStream(true);
        if (request.getTemperature() == null) {
            request.setTemperature(properties.getTemperature());
        }
        if (request.getMaxTokens() == null) {
            request.setMaxTokens(properties.getMaxTokens());
        }
        return new FluxStream(webClient.post()
                .uri("/v1/chat/completions")
                .bodyValue(request)
                .retrieve()
                .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {})
                .map(ServerSentEvent::data)
                .takeUntil("[DONE]"::equals)
                .filter(data -> !"[DONE]".equals(data))
                .mapNotNull(this::parseChunk));
    }

    private StreamChunk parseChunk(String json) {
        try {
            return objectMapper.readValue(json, StreamChunk.class);
        } catch (JsonProcessingException e) {
            log.warn("无法解析流式 chunk: {}", json, e);
            return null;
        }
    }

    /** 对 reactor Flux 的薄封装，隔离上层对 reactor 的直接依赖 */
    public static class FluxStream {
        private final reactor.core.publisher.Flux<StreamChunk> flux;

        FluxStream(reactor.core.publisher.Flux<StreamChunk> flux) {
            this.flux = flux;
        }

        public reactor.core.publisher.Flux<StreamChunk> flux() {
            return flux;
        }
    }

    public String defaultModel() {
        return properties.getModel();
    }

    public String routerModel() {
        String routerModel = properties.getRouterModel();
        return routerModel == null || routerModel.isBlank() ? properties.getModel() : routerModel;
    }
}
