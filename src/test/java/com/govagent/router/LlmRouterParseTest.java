package com.govagent.router;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.govagent.agent.AgentRegistry;
import com.govagent.llm.OpenAiClient;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class LlmRouterParseTest {

    private LlmRouter router() {
        LlmRouter router = new LlmRouter(null, new AgentRegistry(), new ObjectMapper());
        return router;
    }

    @Test
    void parsesAgentId() {
        RouteResult result = router().parse("好的，结果是 {\"agentId\": \"diagnosis\"}");
        assertThat(result.getType()).isEqualTo(RouteResult.Type.AGENT);
        assertThat(result.getAgentId()).isEqualTo("diagnosis");
    }

    @Test
    void parsesClarify() {
        RouteResult result = router().parse("{\"clarify\": \"请问是哪个服务？\"}");
        assertThat(result.getType()).isEqualTo(RouteResult.Type.CLARIFY);
        assertThat(result.getClarifyQuestion()).contains("服务");
    }

    @Test
    void parsesFallback() {
        assertThat(router().parse("{\"fallback\": true}").getType()).isEqualTo(RouteResult.Type.FALLBACK);
    }

    @Test
    void hallucinatedAgentIdFallsBack() {
        // LLM 幻觉出不存在的 agentId → 兜底
        assertThat(router().parse("{\"agentId\": \"nonexistent\"}").getType())
                .isEqualTo(RouteResult.Type.FALLBACK);
    }

    @Test
    void malformedOutputFallsBack() {
        assertThat(router().parse("我觉得应该找故障助手").getType())
                .isEqualTo(RouteResult.Type.FALLBACK);
        assertThat(router().parse(null).getType()).isEqualTo(RouteResult.Type.FALLBACK);
    }

    @Test
    void markdownWrappedJsonParses() {
        RouteResult result = router().parse("```json\n{\"agentId\": \"faq\"}\n```");
        assertThat(result.getType()).isEqualTo(RouteResult.Type.AGENT);
        assertThat(result.getAgentId()).isEqualTo("faq");
    }
}
