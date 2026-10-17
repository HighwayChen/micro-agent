package com.govagent.tool.binding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.govagent.tool.anno.AiTool;
import com.govagent.tool.anno.AiToolParam;
import org.springframework.stereotype.Component;

/**
 * 服务元数据查询（mock 自 metadata center）。
 */
@Component
public class MetadataTools extends AbstractMockBinding {

    @AiTool(name = "list_services", description = "查询平台上的 SOA 服务列表，可按关键词过滤（匹配服务ID或中文名）。用于用户提到服务但服务ID不明确的场景。")
    public JsonNode listServices(
            @AiToolParam(name = "keyword", description = "服务关键词，如：订单、order、库存", required = false) String keyword) {
        ArrayNode result = objectMapper.createArrayNode();
        for (JsonNode service : mockData.get("services").get("services")) {
            if (keyword == null || keyword.isBlank()
                    || service.get("serviceId").asText().contains(keyword)
                    || service.get("name").asText().contains(keyword)) {
                result.add(service);
            }
        }
        return result;
    }

    @AiTool(name = "get_service_instances", description = "查询某服务在指定环境的实例列表（IP、健康状态、在线状态、权重、版本）。不传 env 则返回所有环境。")
    public JsonNode getServiceInstances(
            @AiToolParam(name = "serviceId", description = "服务 ID，如 order-service") String serviceId,
            @AiToolParam(name = "env", description = "环境", required = false, enumValues = {"prod", "stage", "dev"}) String env) {
        JsonNode serviceNode = mockData.get("instances").get(serviceId);
        if (serviceNode == null) {
            return error("服务不存在或无实例数据: " + serviceId);
        }
        if (env == null || env.isBlank()) {
            return serviceNode;
        }
        JsonNode envNode = serviceNode.get(env);
        if (envNode == null) {
            return error("服务 " + serviceId + " 在环境 " + env + " 没有实例");
        }
        return envNode;
    }

    @AiTool(name = "get_service_meta", description = "查询服务元数据：负责人、语言、环境、实例数、功能描述。")
    public JsonNode getServiceMeta(
            @AiToolParam(name = "serviceId", description = "服务 ID") String serviceId) {
        for (JsonNode service : mockData.get("services").get("services")) {
            if (service.get("serviceId").asText().equals(serviceId)) {
                return service;
            }
        }
        return error("服务不存在: " + serviceId);
    }
}
