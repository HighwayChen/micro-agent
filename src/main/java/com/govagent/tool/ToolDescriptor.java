package com.govagent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.govagent.llm.model.ToolDefinition;
import com.govagent.tool.anno.AiTool;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.List;

/**
 * 运行期 tool 描述：反射元数据 + 生成的 OpenAI function schema。
 */
@Getter
@RequiredArgsConstructor
public class ToolDescriptor {

    private final AiTool annotation;
    private final Object bean;
    private final Method method;
    private final List<ParamMeta> params;
    private final ToolDefinition definition;

    public String getName() {
        return annotation.name();
    }

    public boolean isWrite() {
        return annotation.permission() == AiTool.ToolPermission.WRITE;
    }

    /**
     * 按 summaryTemplate={param} 占位符渲染写操作的人类可读摘要。
     */
    public String renderSummary(java.util.Map<String, Object> args) {
        String template = annotation.summaryTemplate().isBlank()
                ? annotation.name() + " " + args
                : annotation.summaryTemplate();
        String rendered = template;
        for (java.util.Map.Entry<String, Object> entry : args.entrySet()) {
            rendered = rendered.replace("{" + entry.getKey() + "}", String.valueOf(entry.getValue()));
        }
        return rendered;
    }

    public record ParamMeta(String name, Parameter parameter, boolean required) {
    }

    public static ToolDescriptor of(AiTool anno, Object bean, Method method, List<ParamMeta> params, JsonNode schema) {
        ToolDefinition definition = ToolDefinition.function(anno.name(), anno.description(), schema);
        return new ToolDescriptor(anno, bean, method, params, definition);
    }

    /** 方法签名快速展示（日志/调试用） */
    public String signature() {
        List<String> names = new ArrayList<>();
        params.forEach(p -> names.add(p.name()));
        return annotation.name() + "(" + String.join(", ", names) + ")";
    }
}
