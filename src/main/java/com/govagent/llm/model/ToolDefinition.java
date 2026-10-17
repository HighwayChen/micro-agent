package com.govagent.llm.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 注入 completions 请求的 tool 定义（OpenAI function calling 格式）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ToolDefinition {

    private String type;
    private FunctionSchema function;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class FunctionSchema {
        private String name;
        private String description;
        private JsonNode parameters;

        public static FunctionSchema of(String name, String description, JsonNode parameters) {
            return FunctionSchema.builder()
                    .name(name)
                    .description(description)
                    .parameters(parameters == null ? emptyObjectSchema() : parameters)
                    .build();
        }

        private static JsonNode emptyObjectSchema() {
            ObjectNode node = JsonNodeFactory.instance.objectNode();
            node.put("type", "object");
            node.set("properties", JsonNodeFactory.instance.objectNode());
            return node;
        }
    }

    public static ToolDefinition function(String name, String description, JsonNode parameters) {
        return ToolDefinition.builder()
                .type("function")
                .function(FunctionSchema.of(name, description, parameters))
                .build();
    }
}
