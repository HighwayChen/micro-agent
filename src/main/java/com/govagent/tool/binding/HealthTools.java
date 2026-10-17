package com.govagent.tool.binding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.govagent.tool.anno.AiTool;
import com.govagent.tool.anno.AiToolParam;
import org.springframework.stereotype.Component;

/**
 * 健康报告与监控指标（mock 自扁鹊健康检查 + 监控系统）。
 */
@Component
public class HealthTools extends AbstractMockBinding {

    @AiTool(name = "get_health_report", description = "查询服务健康体检报告：健康分、等级、各检查项（成功率/延迟/实例健康/GC/CPU）的通过情况与详情。")
    public JsonNode getHealthReport(
            @AiToolParam(name = "serviceId", description = "服务 ID") String serviceId) {
        JsonNode node = mockData.get("health").get(serviceId);
        if (node == null) {
            return error("没有该服务的健康报告: " + serviceId);
        }
        ObjectNode result = ok();
        result.put("serviceId", serviceId);
        result.set("report", node);
        return result;
    }

    @AiTool(name = "get_service_metrics", description = "查询服务当前监控指标：QPS、错误率、P99 延迟及未恢复告警列表。")
    public JsonNode getServiceMetrics(
            @AiToolParam(name = "serviceId", description = "服务 ID") String serviceId) {
        JsonNode node = mockData.get("metrics").get(serviceId);
        if (node == null) {
            return error("没有该服务的监控数据: " + serviceId);
        }
        ObjectNode result = ok();
        result.put("serviceId", serviceId);
        result.set("metrics", node);
        return result;
    }

    @AiTool(name = "get_service_alerts", description = "查询服务当前未恢复的告警列表（P1/P2 级别，含告警内容）。诊断分析的入口。")
    public JsonNode getServiceAlerts(
            @AiToolParam(name = "serviceId", description = "服务 ID") String serviceId) {
        JsonNode node = mockData.get("metrics").get(serviceId);
        if (node == null) {
            return error("没有该服务的告警数据: " + serviceId);
        }
        ObjectNode result = ok();
        result.put("serviceId", serviceId);
        result.set("alerts", node.get("alerts"));
        return result;
    }
}
