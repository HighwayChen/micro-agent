package com.govagent.tool;

import com.govagent.tool.anno.AiTool;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * tool 注册中心：启动时扫描所有 {@link ToolBinding} 实现类中被 @AiTool 标注的方法，
 * 生成 schema 并注册。之后按名称查找，按 Agent 声明的 tool 子集输出。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ToolRegistry {

    private final List<ToolBinding> bindings;
    private final ToolSchemaGenerator schemaGenerator;

    private final Map<String, ToolDescriptor> descriptors = new HashMap<>();

    @PostConstruct
    public void init() {
        for (ToolBinding binding : bindings) {
            for (java.lang.reflect.Method method : binding.getClass().getMethods()) {
                AiTool anno = method.getAnnotation(AiTool.class);
                if (anno == null) {
                    continue;
                }
                if (descriptors.containsKey(anno.name())) {
                    throw new IllegalStateException("重复的 tool 名称: " + anno.name());
                }
                List<ToolDescriptor.ParamMeta> params = schemaGenerator.extractParams(method);
                ToolDescriptor descriptor = ToolDescriptor.of(anno, binding, method, params,
                        schemaGenerator.generate(params));
                descriptors.put(anno.name(), descriptor);
                log.info("注册 tool: {} [{}] {}", descriptor.signature(),
                        descriptor.isWrite() ? "WRITE" : "READ", anno.description());
            }
        }
        log.info("tool 注册完成，共 {} 个", descriptors.size());
    }

    public ToolDescriptor require(String name) {
        ToolDescriptor descriptor = descriptors.get(name);
        if (descriptor == null) {
            throw new IllegalArgumentException("未注册的 tool: " + name);
        }
        return descriptor;
    }

    public List<ToolDescriptor> all() {
        return new ArrayList<>(descriptors.values());
    }

    /** 输出 Agent 声明的 tool 子集对应的定义（注入 LLM 请求） */
    public List<com.govagent.llm.model.ToolDefinition> definitions(List<String> toolNames) {
        List<com.govagent.llm.model.ToolDefinition> result = new ArrayList<>();
        for (String name : toolNames) {
            result.add(require(name).getDefinition());
        }
        return result;
    }
}
