package com.govagent.tool.binding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.govagent.tool.ToolBinding;
import com.govagent.tool.mock.MockDataService;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * mock 绑定类基类：提供数据访问与统一结果构造。
 */
public abstract class AbstractMockBinding implements ToolBinding {

    @Autowired
    protected MockDataService mockData;

    @Autowired
    protected ObjectMapper objectMapper;

    protected ObjectNode ok() {
        return objectMapper.createObjectNode();
    }

    protected JsonNode error(String message) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("error", message);
        return node;
    }
}
