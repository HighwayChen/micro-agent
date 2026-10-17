package com.govagent.tool.binding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.govagent.tool.anno.AiTool;
import com.govagent.tool.anno.AiToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * VI 实例内省工具（mock 自 VI 模块：线程 dump、启动诊断、运行时配置、JVM 指标、定时任务）。
 * <p>
 * thread dump 不能直接返回给 LLM（几十万字符会撑爆上下文），先按调用栈签名聚合成
 * top-N 热点线程组再返回——大文本预处理是这类 tool 的关键设计。
 */
@Component
public class ViTools extends AbstractMockBinding {

    private static final Pattern THREAD_HEADER = Pattern.compile("^\"([^\"]+)\".*cpu=([\\d.]+)ms");
    private static final Pattern STATE_LINE = Pattern.compile("java.lang.Thread.State:\\s*(\\S+)");
    private static final String DUMP_OWNER = "inventory-service";
    private static final String DUMP_INSTANCE = "10.2.3.4";

    @AiTool(name = "analyze_thread_dump", description = "获取实例的线程 dump 并聚合分析：按调用栈签名分组统计热点线程（线程数、CPU 占用、代表性线程名、热点栈帧）。适用于 CPU 高、超时、线程池打满类问题。")
    public JsonNode analyzeThreadDump(
            @AiToolParam(name = "serviceId", description = "服务 ID") String serviceId) {
        if (!DUMP_OWNER.equals(serviceId)) {
            return error("该服务暂无可用的线程 dump（mock 数据覆盖: " + DUMP_OWNER + " " + DUMP_INSTANCE + "）");
        }
        return aggregate(serviceId, DUMP_INSTANCE, mockData.getText("thread-dump.txt"));
    }

    /** 按「完整调用栈签名」分组聚合，输出热点 top 组 */
    public JsonNode aggregate(String serviceId, String ip, String dump) {
        record ThreadRecord(String name, String state, double cpuMs, List<String> frames) {
        }
        List<ThreadRecord> threads = new ArrayList<>();
        String currentName = null;
        String currentState = "";
        double currentCpu = 0;
        List<String> currentFrames = new ArrayList<>();

        for (String line : dump.split("\n")) {
            Matcher header = THREAD_HEADER.matcher(line);
            if (header.find()) {
                if (currentName != null) {
                    threads.add(new ThreadRecord(currentName, currentState, currentCpu, List.copyOf(currentFrames)));
                }
                currentName = header.group(1);
                currentState = "";
                currentCpu = Double.parseDouble(header.group(2));
                currentFrames = new ArrayList<>();
                continue;
            }
            Matcher state = STATE_LINE.matcher(line);
            if (state.find() && currentName != null) {
                currentState = state.group(1);
                continue;
            }
            String trimmed = line.trim();
            if (trimmed.startsWith("at ") && currentFrames.size() < 12) {
                currentFrames.add(trimmed.substring(3));
            }
        }
        if (currentName != null) {
            threads.add(new ThreadRecord(currentName, currentState, currentCpu, List.copyOf(currentFrames)));
        }

        // 分组 key = 栈签名；同栈线程合并统计
        Map<String, List<ThreadRecord>> groups = new LinkedHashMap<>();
        for (ThreadRecord thread : threads) {
            groups.computeIfAbsent(String.join("|", thread.frames()), k -> new ArrayList<>()).add(thread);
        }

        List<Map.Entry<String, List<ThreadRecord>>> sorted = new ArrayList<>(groups.entrySet());
        sorted.sort(Comparator.comparingInt((Map.Entry<String, List<ThreadRecord>> e) -> e.getValue().size()).reversed());

        ArrayNode hotspots = objectMapper.createArrayNode();
        int rank = 1;
        for (Map.Entry<String, List<ThreadRecord>> entry : sorted) {
            List<ThreadRecord> group = entry.getValue();
            ObjectNode hotspot = ok();
            hotspot.put("rank", rank++);
            hotspot.put("threadCount", group.size());
            hotspot.put("state", group.get(0).state());
            hotspot.put("totalCpuMs", group.stream().mapToDouble(ThreadRecord::cpuMs).sum());
            hotspot.put("representThread", group.get(0).name());
            ArrayNode frames = hotspot.putArray("topFrames");
            group.get(0).frames().stream().limit(6).forEach(frames::add);
            hotspots.add(hotspot);
            if (rank > 5) {
                break;
            }
        }

        ObjectNode result = ok();
        result.put("serviceId", serviceId);
        result.put("instance", ip);
        result.put("totalThreads", threads.size());
        result.put("analysisNote", "热点线程集中在 SOA RPC 调用与库存扣减/防超卖校验链路，存在数据库连接池等待（HikariCP borrow），建议结合生效配置中的连接池/超时参数分析");
        result.set("hotspots", hotspots);
        return result;
    }

    @AiTool(name = "get_startup_failures", description = "查询实例启动失败诊断：失败服务、阶段（端口绑定/配置加载等）、错误信息、日志摘录与修复建议。")
    public JsonNode getStartupFailures(
            @AiToolParam(name = "serviceId", description = "服务 ID，可选", required = false) String serviceId) {
        ArrayNode result = objectMapper.createArrayNode();
        for (JsonNode failure : mockData.get("startup-failure").get("failures")) {
            if (serviceId == null || serviceId.isBlank() || failure.get("serviceId").asText().equals(serviceId)) {
                result.add(failure);
            }
        }
        return result;
    }

    @AiTool(name = "get_effective_config", description = "查询实例运行时生效配置（来自 VI 端点，比 Apollo 配置更真实——能发现『发布后实例未重启仍持旧配置』的问题）。")
    public JsonNode getEffectiveConfig(
            @AiToolParam(name = "serviceId", description = "服务 ID") String serviceId) {
        JsonNode node = mockData.get("effective-config").get(serviceId);
        if (node == null) {
            return error("没有该服务的运行时配置数据: " + serviceId);
        }
        ObjectNode result = ok();
        result.put("serviceId", serviceId);
        result.set("instances", node);
        return result;
    }

    @AiTool(name = "get_jvm_metrics", description = "查询实例 JVM 实时指标：CPU、堆内存、GC 次数与耗时、线程数。")
    public JsonNode getJvmMetrics(
            @AiToolParam(name = "serviceId", description = "服务 ID") String serviceId) {
        JsonNode node = mockData.get("jvm-metrics").get(serviceId);
        if (node == null) {
            return error("没有该服务的 JVM 指标: " + serviceId);
        }
        ObjectNode result = ok();
        result.put("serviceId", serviceId);
        result.set("instances", node);
        return result;
    }

    @AiTool(name = "get_scheduled_tasks", description = "查询实例的 Spring 定时任务清单：任务名、cron、运行状态、最近一次执行耗时。")
    public JsonNode getScheduledTasks(
            @AiToolParam(name = "serviceId", description = "服务 ID") String serviceId) {
        JsonNode node = mockData.get("scheduled-tasks").get(serviceId);
        if (node == null) {
            return error("没有该服务的定时任务数据: " + serviceId);
        }
        ObjectNode result = ok();
        result.put("serviceId", serviceId);
        result.set("instances", node);
        return result;
    }
}
