package com.govagent.tool.binding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.govagent.tool.ToolContext;
import com.govagent.tool.anno.AiTool;
import com.govagent.tool.anno.AiToolParam;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 写操作工具（mock 执行真实效果：变更内存中的 mock 数据）。
 * 这些方法被 ToolExecutor 拦截，只有用户在聊天页确认预案后才真正调用。
 */
@Component
public class WriteTools extends AbstractMockBinding {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @AiTool(name = "pull_out_instance",
            description = "将某服务的指定实例摘除流量（健康检查异常实例止损的常用手段）。属于写操作，需要用户确认。",
            permission = AiTool.ToolPermission.WRITE,
            riskDesc = "实例摘除后该环境容量减少，若摘除过多实例可能导致服务不可用",
            summaryTemplate = "摘除 {serviceId}（{env}）实例 {ip} 的流量")
    public JsonNode pullOutInstance(
            @AiToolParam(name = "serviceId", description = "服务 ID") String serviceId,
            @AiToolParam(name = "env", description = "环境", enumValues = {"prod", "stage", "dev"}) String env,
            @AiToolParam(name = "ip", description = "实例 IP") String ip,
            ToolContext ctx) {
        JsonNode instance = findInstance(serviceId, env, ip);
        if (instance == null) {
            return error("实例不存在: " + serviceId + "/" + env + "/" + ip);
        }
        if (instance instanceof ObjectNode node) {
            node.put("status", "PULLED_OUT");
            node.put("weight", 0);
            node.put("note", "已由 " + ctx.getUsername() + " 于 " + TIME.format(LocalDateTime.now()) + " 摘除流量");
        }
        return receipt("摘除流量成功", ctx, serviceId + "/" + env + "/" + ip);
    }

    @AiTool(name = "pull_in_instance",
            description = "将此前被摘除的实例重新拉入流量（恢复服务）。属于写操作，需要用户确认。",
            permission = AiTool.ToolPermission.WRITE,
            riskDesc = "实例拉入后会立即接收流量，若故障未恢复错误率将回升",
            summaryTemplate = "恢复 {serviceId}（{env}）实例 {ip} 的流量")
    public JsonNode pullInInstance(
            @AiToolParam(name = "serviceId", description = "服务 ID") String serviceId,
            @AiToolParam(name = "env", description = "环境", enumValues = {"prod", "stage", "dev"}) String env,
            @AiToolParam(name = "ip", description = "实例 IP") String ip,
            ToolContext ctx) {
        JsonNode instance = findInstance(serviceId, env, ip);
        if (instance == null) {
            return error("实例不存在: " + serviceId + "/" + env + "/" + ip);
        }
        if (instance instanceof ObjectNode node) {
            node.put("status", "HEALTHY");
            node.put("weight", 100);
            node.remove("note");
        }
        return receipt("恢复流量成功", ctx, serviceId + "/" + env + "/" + ip);
    }

    @AiTool(name = "update_apollo_config",
            description = "修改服务的 Apollo 配置项并发布（所有环境生效）。属于写操作，需要用户确认。",
            permission = AiTool.ToolPermission.WRITE,
            riskDesc = "配置发布后立即生效且影响全部实例，配置错误可能引发故障",
            summaryTemplate = "修改 {serviceId} 配置 {namespace}/{key} = {value}")
    public JsonNode updateApolloConfig(
            @AiToolParam(name = "serviceId", description = "服务 ID") String serviceId,
            @AiToolParam(name = "namespace", description = "Apollo namespace，如 SOA.RPC") String namespace,
            @AiToolParam(name = "key", description = "配置 key") String key,
            @AiToolParam(name = "value", description = "新值") String value,
            ToolContext ctx) {
        // mock 效果：追加一条变更记录，并同步运行时生效配置
        ArrayNode changes = (ArrayNode) mockData.get("apollo-items").get("changes");
        ObjectNode change = ok();
        change.put("serviceId", serviceId);
        change.put("namespace", namespace);
        change.put("key", key);
        change.put("oldValue", "(mock: 未知)");
        change.put("newValue", value);
        change.put("operator", ctx.getUsername());
        change.put("time", TIME.format(LocalDateTime.now()));
        change.put("releaseTitle", "AI 助手代操作");
        changes.add(change);

        JsonNode effective = mockData.get("effective-config").get(serviceId);
        if (effective instanceof ObjectNode serviceNode) {
            serviceNode.properties().forEach(entry -> {
                if (entry.getValue() instanceof ObjectNode instanceConfig) {
                    instanceConfig.put(key, value);
                }
            });
        }
        return receipt("配置发布成功: " + namespace + "/" + key + "=" + value, ctx, serviceId);
    }

    @AiTool(name = "evict_spring_cache",
            description = "清除实例的 Spring 缓存（VI 能力，用于配置变更后缓存不一致的排查）。属于写操作，需要用户确认。",
            permission = AiTool.ToolPermission.WRITE,
            riskDesc = "缓存清除后短期内下游压力上升（缓存击穿）",
            summaryTemplate = "清除 {serviceId} 实例 {ip} 的缓存 {cacheName}")
    public JsonNode evictSpringCache(
            @AiToolParam(name = "serviceId", description = "服务 ID") String serviceId,
            @AiToolParam(name = "ip", description = "实例 IP，传 all 表示全部实例", required = false) String ip,
            @AiToolParam(name = "cacheName", description = "缓存名，传 all 表示全部缓存") String cacheName,
            ToolContext ctx) {
        return receipt("缓存清除成功(" + (ip == null ? "all" : ip) + "/" + cacheName + ")", ctx, serviceId);
    }

    private JsonNode findInstance(String serviceId, String env, String ip) {
        JsonNode envNode = mockData.get("instances").path(serviceId).path(env);
        for (JsonNode instance : envNode) {
            if (instance.get("ip").asText().equals(ip)) {
                return instance;
            }
        }
        return null;
    }

    private ObjectNode receipt(String message, ToolContext ctx, String target) {
        ObjectNode receipt = ok();
        receipt.put("status", "SUCCESS");
        receipt.put("message", message);
        receipt.put("target", target);
        receipt.put("operator", ctx.getUsername());
        receipt.put("time", TIME.format(LocalDateTime.now()));
        receipt.put("mock", true);
        return receipt;
    }
}
