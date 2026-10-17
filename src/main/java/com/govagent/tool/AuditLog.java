package com.govagent.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * tool 调用审计（内存实现）。真实场景应落 oplog/DB。
 */
@Slf4j
@Component
public class AuditLog {

    public record AuditRecord(Instant time, String toolName, String permission, String username,
                              String sessionId, String argsJson, boolean executed, long costMs, String error) {
    }

    private final List<AuditRecord> records = new CopyOnWriteArrayList<>();
    private final Map<String, Integer> invokeCount = new ConcurrentHashMap<>();

    public void record(ToolDescriptor descriptor, ToolContext ctx, String argsJson,
                       boolean executed, long costMs, String error) {
        records.add(new AuditRecord(Instant.now(), descriptor.getName(),
                descriptor.isWrite() ? "WRITE" : "READ",
                ctx.getUsername(), ctx.getSessionId(), argsJson, executed, costMs, error));
        invokeCount.merge(descriptor.getName(), 1, Integer::sum);
        if (error != null) {
            log.warn("[audit] {} {} 失败: {}", ctx.getUsername(), descriptor.getName(), error);
        } else {
            log.info("[audit] {} {} {} ({}ms)", ctx.getUsername(), descriptor.getName(),
                    executed ? "执行" : "拦截待确认", costMs);
        }
    }

    public List<AuditRecord> recent(int limit) {
        int size = records.size();
        return records.subList(Math.max(0, size - limit), size);
    }

    public int countOf(String toolName) {
        return invokeCount.getOrDefault(toolName, 0);
    }
}
