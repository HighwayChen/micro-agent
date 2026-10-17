package com.govagent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.govagent.tool.binding.ViTools;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class ViToolsAggregationTest {

    private ViTools viTools;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper();
        viTools = new ViTools();
        ReflectionTestUtils.setField(viTools, "mockData",
                new com.govagent.tool.mock.MockDataService(mapper));
        ReflectionTestUtils.setField(viTools, "objectMapper", mapper);
    }

    @Test
    void threadDumpAggregatesHotThreadsByStackSignature() {
        JsonNode result = viTools.analyzeThreadDump("inventory-service");

        assertThat(result.has("hotspots")).isTrue();
        JsonNode hotspots = result.get("hotspots");
        assertThat(hotspots.size()).isGreaterThan(1);
        assertThat(result.get("totalThreads").asInt()).isEqualTo(8);

        // 第一热点：4 个同栈的 rpc-worker 线程
        JsonNode top1 = hotspots.get(0);
        assertThat(top1.get("threadCount").asInt()).isEqualTo(4);
        assertThat(top1.get("representThread").asText()).isEqualTo("inventory-rpc-worker-1");
        assertThat(top1.get("topFrames").toString()).contains("StockDeductService.deduct");

        // 堆排序：线程数多的组在前
        assertThat(top1.get("threadCount").asInt())
                .isGreaterThanOrEqualTo(hotspots.get(1).get("threadCount").asInt());
    }

    @Test
    void unsupportedServiceReturnsError() {
        JsonNode result = viTools.analyzeThreadDump("order-service");
        assertThat(result.get("error").asText()).contains("暂无");
    }

    @Test
    void aggregationTruncatesFramesPerThread() {
        String manyFrames = "\"t1\" #1 cpu=10.00ms\n"
                + "   java.lang.Thread.State: RUNNABLE\n"
                + String.join("\n", java.util.stream.IntStream.rangeClosed(1, 50)
                        .mapToObj(i -> "\tat com.example.Frame" + i + ".call(Frame" + i + ".java:" + i + ")")
                        .toList())
                + "\n";
        JsonNode result = viTools.aggregate("x-service", "1.1.1.1", manyFrames);
        assertThat(result.get("totalThreads").asInt()).isEqualTo(1);
        // 每线程最多记录 12 帧，避免长栈撑爆分组 key
        assertThat(result.get("hotspots").get(0).get("topFrames").size()).isEqualTo(6);
    }
}
