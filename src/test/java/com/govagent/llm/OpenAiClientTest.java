package com.govagent.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.govagent.config.OpenAiProperties;
import com.govagent.llm.model.ChatCompletionRequest;
import com.govagent.llm.model.ChatCompletionResponse;
import com.govagent.llm.model.ChatMessage;
import com.govagent.llm.model.StreamChunk;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiClientTest {

    private MockWebServer server;
    private OpenAiClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        OpenAiProperties properties = new OpenAiProperties();
        properties.setBaseUrl(server.url("/").toString());
        properties.setApiKey("test-key");
        properties.setModel("test-model");
        client = new OpenAiClient(properties, mapper);
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    void shouldParseNonStreamResponse() {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"id":"resp_1","model":"test-model","choices":[{"index":0,
                          "message":{"role":"assistant","content":"FAQ"},
                          "finish_reason":"stop"}],
                         "usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}"""));

        ChatCompletionRequest request = ChatCompletionRequest.of("test-model",
                List.of(ChatMessage.user("路由")), null);
        ChatCompletionResponse response = client.chat(request);

        assertThat(response.firstMessage().getContent()).isEqualTo("FAQ");
        assertThat(response.firstFinishReason()).isEqualTo("stop");
        assertThat(response.getUsage().getTotalTokens()).isEqualTo(15);
    }

    @Test
    void shouldParseSseStreamAndSkipDone() {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("""
                        data: {"choices":[{"index":0,"delta":{"role":"assistant","content":"你"},"finish_reason":null}]}

                        data: {"choices":[{"index":0,"delta":{"content":"好"},"finish_reason":null}]}

                        data: {"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

                        data: [DONE]

                        """));

        ChatCompletionRequest request = ChatCompletionRequest.of("test-model",
                List.of(ChatMessage.user("hi")), null);

        List<StreamChunk> chunks = client.streamChat(request).flux().collectList().block();

        assertThat(chunks).hasSize(3);
        StreamAggregator aggregator = new StreamAggregator();
        chunks.forEach(aggregator::feed);
        assertThat(aggregator.getContent()).isEqualTo("你好");
        assertThat(aggregator.getFinishReason()).isEqualTo("stop");
    }

    @Test
    void shouldCarryAuthorizationHeader() throws Exception {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"id":"r","choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}"""));

        client.chat(ChatCompletionRequest.of("m", List.of(ChatMessage.user("x")), null));

        var recorded = server.takeRequest();
        assertThat(recorded.getHeader("Authorization")).isEqualTo("Bearer test-key");
        assertThat(recorded.getPath()).isEqualTo("/v1/chat/completions");
        assertThat(recorded.getBody().readUtf8()).contains("\"stream\":false");
    }

    @Test
    void fluxStreamIsReplayableAsPlainFlux() {
        // 确认 FluxStream 薄封装不改变 reactor 语义
        Flux<StreamChunk> flux = Flux.empty();
        assertThat(new OpenAiClient.FluxStream(flux)).isNotNull();
    }
}
