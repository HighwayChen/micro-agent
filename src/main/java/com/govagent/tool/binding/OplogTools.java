package com.govagent.tool.binding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.govagent.tool.anno.AiTool;
import com.govagent.tool.anno.AiToolParam;
import org.springframework.stereotype.Component;

/**
 * 操作记录查询（mock 自治理 oplog + Apollo 发布历史）。
 */
@Component
public class OplogTools extends AbstractMockBinding {

    @AiTool(name = "get_recent_releases", description = "查询最近的发布/重启/操作记录。故障时间点附近是否有人变更，是根因定位的关键证据。可按服务过滤。")
    public JsonNode getRecentReleases(
            @AiToolParam(name = "serviceId", description = "服务 ID，可选", required = false) String serviceId) {
        ArrayNode result = objectMapper.createArrayNode();
        for (JsonNode log : mockData.get("oplog").get("logs")) {
            if (serviceId == null || serviceId.isBlank() || log.get("target").asText().equals(serviceId)) {
                result.add(log);
            }
        }
        return result;
    }

    @AiTool(name = "get_recent_config_changes", description = "查询最近的 Apollo 配置变更记录（改了什么 key、旧值新值、操作人、时间、发布标题）。可按服务过滤。")
    public JsonNode getRecentConfigChanges(
            @AiToolParam(name = "serviceId", description = "服务 ID，可选", required = false) String serviceId) {
        ArrayNode result = objectMapper.createArrayNode();
        for (JsonNode change : mockData.get("apollo-items").get("changes")) {
            if (serviceId == null || serviceId.isBlank() || change.get("serviceId").asText().equals(serviceId)) {
                result.add(change);
            }
        }
        return result;
    }
}
