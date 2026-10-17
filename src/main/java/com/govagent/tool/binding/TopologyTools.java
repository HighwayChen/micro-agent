package com.govagent.tool.binding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.govagent.tool.anno.AiTool;
import com.govagent.tool.anno.AiToolParam;
import org.springframework.stereotype.Component;

/**
 * 服务依赖拓扑查询（mock 自依赖分析系统）。
 */
@Component
public class TopologyTools extends AbstractMockBinding {

    @AiTool(name = "get_service_topology", description = "查询服务的依赖拓扑：它调用了哪些下游服务（dependsOn，含协议/QPS），以及哪些上游服务在调用它（dependedBy）。故障排查第一步。")
    public JsonNode getServiceTopology(
            @AiToolParam(name = "serviceId", description = "服务 ID") String serviceId) {
        JsonNode node = mockData.get("topology").get(serviceId);
        if (node == null) {
            return error("没有该服务的拓扑数据: " + serviceId);
        }
        ObjectNode result = ok();
        result.put("serviceId", serviceId);
        result.set("dependsOn", node.get("dependsOn"));
        result.set("dependedBy", node.get("dependedBy"));
        return result;
    }
}
