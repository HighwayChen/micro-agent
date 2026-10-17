package com.govagent;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 端到端：MockWebServer 模拟 OpenAI 兼容网关，验证
 * 路由 → ReAct（tool calling 两轮流式）→ tool 执行（mock 数据）→ SSE 事件序列。
 * 注意：stub 的 URL 通过系统属性 llm.stub.url 注入，必须在 Spring 上下文加载前设置
 * （JUnit @BeforeAll 先于上下文创建实例时执行，顺序成立）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "agent.llm.base-url=${llm.stub.url}",
        "agent.llm.api-key=test",
        "agent.llm.model=test-model"
})
class ChatEndToEndTest {

    static MockWebServer llm;

    @LocalServerPort
    int port;

    @BeforeAll
    static void startStub() throws Exception {
        llm = new MockWebServer();
        llm.start();
        System.setProperty("llm.stub.url", llm.url("/").toString());

        // 1) 路由（非流式）：命中 diagnosis
        llm.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody("""
                {"id":"r1","choices":[{"index":0,"message":{"role":"assistant",
                  "content":"{\\"agentId\\": \\"diagnosis\\"}"},"finish_reason":"stop"}]}"""));

        // 2) 第一轮流式：发起 tool call（get_service_alerts，参数分两片到达）
        llm.enqueue(new MockResponse().setHeader("Content-Type", "text/event-stream").setBody("""
                data: {"choices":[{"index":0,"delta":{"role":"assistant","tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"get_service_alerts","arguments":"{\\"serviceId\\":\\"invent"}}]},"finish_reason":null}]}

                data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"ory-service\\"}"}}]},"finish_reason":null}]}

                data: {"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}

                data: [DONE]

                """));

        // 3) 第二轮流式：基于 tool 结果输出结论
        llm.enqueue(new MockResponse().setHeader("Content-Type", "text/event-stream").setBody("""
                data: {"choices":[{"index":0,"delta":{"role":"assistant","content":"根因是"},"finish_reason":null}]}

                data: {"choices":[{"index":0,"delta":{"content":"配置超时时间被改小"},"finish_reason":null}]}

                data: {"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

                data: [DONE]

                """));
    }

    @AfterAll
    static void stopStub() throws Exception {
        llm.shutdown();
    }

    @Test
    void chatRoutesDiagnosesAndRunsTools() throws Exception {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/chat"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"message\":\"库存服务为什么报错\"}"))
                .build();

        HttpResponse<java.util.stream.Stream<String>> response =
                client.send(request, HttpResponse.BodyHandlers.ofLines());
        List<String> lines = response.body().toList();
        String all = String.join("\n", lines);

        assertThat(response.statusCode()).isEqualTo(200);
        // 事件序列
        assertThat(all).contains("event:message_start");
        assertThat(all).contains("event:tool_call");
        assertThat(all).contains("event:tool_result");
        assertThat(all).contains("event:token");
        assertThat(all).contains("event:done");
        // 路由到诊断 agent
        assertThat(all).contains("故障诊断");
        // tool 名称与分片聚合后的参数正确
        assertThat(all).contains("get_service_alerts");
        // mock 数据进入 tool 结果（P1 告警）
        assertThat(all).contains("P1");
        // 流式 token 两次分片都被透出
        assertThat(all).contains("根因是");
        assertThat(all).contains("配置超时时间被改小");
        assertThat(all).contains("\"finishReason\":\"stop\"");
    }
}
