package com.govagent.tool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.govagent.config.AgentProperties;
import com.govagent.confirm.PendingOpStore;
import com.govagent.confirm.PendingOperation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * tool 统一执行入口：鉴权 → 写操作拦截 → 审计 → 反射调用。
 * <p>
 * READ  → 直接执行
 * WRITE → 不执行，生成 PendingOperation 返回（人工确认状态机入口）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ToolExecutor {

    private final ToolRegistry registry;
    private final PendingOpStore pendingOpStore;
    private final AuditLog auditLog;
    private final AgentProperties properties;
    private final ObjectMapper objectMapper;

    public ToolExecutionResult execute(String toolName, String argsJson, ToolContext ctx) {
        ToolDescriptor descriptor;
        try {
            descriptor = registry.require(toolName);
        } catch (IllegalArgumentException e) {
            return ToolExecutionResult.error(e.getMessage(), 0);
        }

        Map<String, Object> args = parseArgs(argsJson);

        // 写权限校验（角色白名单）
        if (descriptor.isWrite() && !ctx.hasAnyRole(properties.getWriteRoles())) {
            String error = "当前用户无写操作权限，需要角色: " + properties.getWriteRoles();
            auditLog.record(descriptor, ctx, argsJson, false, 0, error);
            return ToolExecutionResult.error(error, 0);
        }

        // 写操作拦截：生成待确认预案
        if (descriptor.isWrite()) {
            PendingOperation op = pendingOpStore.create(
                    descriptor.getName(), argsJson,
                    descriptor.renderSummary(args),
                    descriptor.getAnnotation().riskDesc(),
                    ctx.getUsername(), ctx.getSessionId(),
                    properties.getConfirmTtlSeconds());
            auditLog.record(descriptor, ctx, argsJson, false, 0, null);
            return ToolExecutionResult.confirmRequired(op);
        }

        // 只读：直接执行
        long start = System.currentTimeMillis();
        try {
            Object result = invoke(descriptor, args, ctx);
            long cost = System.currentTimeMillis() - start;
            String json = toTruncatedJson(result, cost);
            auditLog.record(descriptor, ctx, argsJson, true, cost, null);
            return ToolExecutionResult.executed(json, cost);
        } catch (Exception e) {
            long cost = System.currentTimeMillis() - start;
            log.error("tool 执行异常: {}", descriptor.getName(), e);
            auditLog.record(descriptor, ctx, argsJson, true, cost, e.getMessage());
            return ToolExecutionResult.error("执行失败: " + e.getMessage(), cost);
        }
    }

    /**
     * 预案确认后的写操作真正执行（绕过 WRITE 拦截——权限已通过确认流程授予）。
     */
    public String executeConfirmed(String toolName, String argsJson, ToolContext ctx) throws Exception {
        ToolDescriptor descriptor = registry.require(toolName);
        long start = System.currentTimeMillis();
        try {
            Object result = invoke(descriptor, parseArgs(argsJson), ctx);
            String json = toTruncatedJson(result, System.currentTimeMillis() - start);
            auditLog.record(descriptor, ctx, argsJson, true, System.currentTimeMillis() - start, null);
            return json;
        } catch (Exception e) {
            auditLog.record(descriptor, ctx, argsJson, true, System.currentTimeMillis() - start, e.getMessage());
            throw e;
        }
    }

    private Object invoke(ToolDescriptor descriptor, Map<String, Object> args, ToolContext ctx) throws Exception {
        Method method = descriptor.getMethod();
        Parameter[] parameters = method.getParameters();
        Object[] argv = new Object[parameters.length];
        int toolParamIndex = 0;
        for (int i = 0; i < parameters.length; i++) {
            if (ToolContext.class.isAssignableFrom(parameters[i].getType())) {
                argv[i] = ctx;
            } else {
                String paramName = descriptor.getParams().get(toolParamIndex).name();
                argv[i] = convert(args.get(paramName), parameters[i].getType());
                toolParamIndex++;
            }
        }
        try {
            return method.invoke(descriptor.getBean(), argv);
        } catch (java.lang.reflect.InvocationTargetException e) {
            // 解包反射异常，让上层拿到真实错误信息
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof Exception ex) {
                throw ex;
            }
            throw e;
        }
    }

    private Object convert(Object value, Class<?> targetType) {
        if (value == null) {
            return null;
        }
        return objectMapper.convertValue(value, targetType);
    }

    private Map<String, Object> parseArgs(String argsJson) {
        if (argsJson == null || argsJson.isBlank() || "null".equals(argsJson)) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(argsJson, new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (Exception e) {
            log.warn("tool 参数解析失败: {}", argsJson, e);
            return new LinkedHashMap<>();
        }
    }

    /** 结果序列化并按上限截断，避免撑爆上下文 */
    private String toTruncatedJson(Object result, long cost) throws Exception {
        String json;
        if (result instanceof String s) {
            json = s;
        } else {
            json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result);
        }
        int maxBytes = properties.getResultMaxBytes();
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) {
            return json;
        }
        String truncated = new String(json.getBytes(StandardCharsets.UTF_8), 0, maxBytes, StandardCharsets.UTF_8);
        return truncated + "\n...[结果过大已截断，原始 " + bytes.length + " 字节，耗时 " + cost + "ms]";
    }
}
