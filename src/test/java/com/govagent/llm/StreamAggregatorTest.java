package com.govagent.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.govagent.llm.model.ChatMessage;
import com.govagent.llm.model.StreamChunk;
import com.govagent.llm.model.ToolCall;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StreamAggregatorTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private StreamChunk chunk(String json) throws Exception {
        return mapper.readValue(json, StreamChunk.class);
    }

    @Test
    void shouldAggregateFragmentedToolCallArguments() throws Exception {
        StreamAggregator aggregator = new StreamAggregator();
        // OpenAI 真实场景：首个分片带 id+name，arguments 被切成多片
        aggregator.feed(chunk("""
                {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function",
                  "function":{"name":"get_instances","arguments":"{\\"serviceId\\":\\"ord"}}]},"finish_reason":null}]}"""));
        aggregator.feed(chunk("""
                {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,
                  "function":{"arguments":"er-service\\"}"}}]},"finish_reason":null}]}"""));
        aggregator.feed(chunk("""
                {"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}"""));

        assertThat(aggregator.wantsToolCall()).isTrue();

        ChatMessage message = aggregator.buildAssistantMessage();
        assertThat(message.getRole()).isEqualTo("assistant");
        assertThat(message.getContent()).isNull();
        assertThat(message.getToolCalls()).hasSize(1);

        ToolCall toolCall = message.getToolCalls().get(0);
        assertThat(toolCall.getId()).isEqualTo("call_1");
        assertThat(toolCall.getFunction().getName()).isEqualTo("get_instances");
        assertThat(toolCall.getFunction().getArguments()).isEqualTo("{\"serviceId\":\"order-service\"}");
    }

    @Test
    void shouldAggregateMultipleToolCallsInOneRound() throws Exception {
        StreamAggregator aggregator = new StreamAggregator();
        aggregator.feed(chunk("""
                {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_a","function":{"name":"t1","arguments":"{"}},
                                                   {"index":1,"id":"call_b","function":{"name":"t2","arguments":"["}}]},"finish_reason":null}]}"""));
        aggregator.feed(chunk("""
                {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":")"}},
                                                   {"index":1,"function":{"arguments":"]"}}]},"finish_reason":null}]}"""));
        aggregator.feed(chunk("""
                {"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}"""));

        List<ToolCall> calls = aggregator.buildAssistantMessage().getToolCalls();
        assertThat(calls).hasSize(2);
        assertThat(calls.get(0).getFunction().getArguments()).isEqualTo("{)");
        assertThat(calls.get(1).getFunction().getArguments()).isEqualTo("[]");
    }

    @Test
    void shouldEmitContentTokensIncrementally() throws Exception {
        StreamAggregator aggregator = new StreamAggregator();
        String t1 = aggregator.feed(chunk("""
                {"choices":[{"index":0,"delta":{"role":"assistant","content":"订单"},"finish_reason":null}]}"""));
        String t2 = aggregator.feed(chunk("""
                {"choices":[{"index":0,"delta":{"content":"服务超时"},"finish_reason":null}]}"""));
        String t3 = aggregator.feed(chunk("""
                {"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}"""));

        assertThat(t1).isEqualTo("订单");
        assertThat(t2).isEqualTo("服务超时");
        assertThat(t3).isNull();
        assertThat(aggregator.wantsToolCall()).isFalse();
        assertThat(aggregator.getContent()).isEqualTo("订单服务超时");
        assertThat(aggregator.buildAssistantMessage().getToolCalls()).isNull();
    }

    @Test
    void shouldHandleContentAndToolCallInterleaved() throws Exception {
        StreamAggregator aggregator = new StreamAggregator();
        String token = aggregator.feed(chunk("""
                {"choices":[{"index":0,"delta":{"content":"我先查一下实例。"},"finish_reason":null}]}"""));
        aggregator.feed(chunk("""
                {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_x","function":{"name":"query","arguments":"{}"}}]},"finish_reason":null}]}"""));
        aggregator.feed(chunk("""
                {"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}"""));

        assertThat(token).isEqualTo("我先查一下实例。");
        ChatMessage message = aggregator.buildAssistantMessage();
        assertThat(message.getContent()).isEqualTo("我先查一下实例。");
        assertThat(message.getToolCalls()).hasSize(1);
    }
}
