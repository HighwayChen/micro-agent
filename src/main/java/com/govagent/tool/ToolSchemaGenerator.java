package com.govagent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.govagent.tool.anno.AiToolParam;
import org.springframework.stereotype.Component;

import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.List;

/**
 * 注解 + 反射 → OpenAI function JSON Schema。启动期一次性完成。
 * <p>
 * 类型映射：String→string，int/long→integer，double/float→number，boolean→boolean，其余按 string 处理。
 */
@Component
public class ToolSchemaGenerator {

    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    public JsonNode generate(List<ToolDescriptor.ParamMeta> params) {
        ObjectNode schema = F.objectNode();
        schema.put("type", "object");
        ObjectNode properties = F.objectNode();
        ArrayNode required = F.arrayNode();

        for (ToolDescriptor.ParamMeta meta : params) {
            Parameter parameter = meta.parameter();
            AiToolParam anno = parameter.getAnnotation(AiToolParam.class);
            ObjectNode prop = F.objectNode();
            prop.put("type", mapType(parameter.getType()));
            if (anno != null && !anno.description().isBlank()) {
                prop.put("description", anno.description());
            }
            if (anno != null && anno.enumValues().length > 0) {
                ArrayNode enums = F.arrayNode();
                for (String value : anno.enumValues()) {
                    enums.add(value);
                }
                prop.set("enum", enums);
            }
            properties.set(meta.name(), prop);
            if (meta.required()) {
                required.add(meta.name());
            }
        }
        schema.set("properties", properties);
        if (!required.isEmpty()) {
            schema.set("required", required);
        }
        return schema;
    }

    private String mapType(Class<?> type) {
        if (type == String.class) {
            return "string";
        }
        if (type == int.class || type == Integer.class || type == long.class || type == Long.class) {
            return "integer";
        }
        if (type == double.class || type == Double.class || type == float.class || type == Float.class) {
            return "number";
        }
        if (type == boolean.class || type == Boolean.class) {
            return "boolean";
        }
        return "string";
    }

    /** 从方法参数提取 ParamMeta（ToolContext 类型参数跳过，不暴露给 LLM） */
    public List<ToolDescriptor.ParamMeta> extractParams(java.lang.reflect.Method method) {
        List<ToolDescriptor.ParamMeta> params = new ArrayList<>();
        for (Parameter parameter : method.getParameters()) {
            if (ToolContext.class.isAssignableFrom(parameter.getType())) {
                continue;
            }
            AiToolParam anno = parameter.getAnnotation(AiToolParam.class);
            String name = anno != null ? anno.name() : parameter.getName();
            boolean required = anno == null || anno.required();
            params.add(new ToolDescriptor.ParamMeta(name, parameter, required));
        }
        return params;
    }
}
