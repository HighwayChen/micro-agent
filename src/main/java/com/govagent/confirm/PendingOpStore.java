package com.govagent.confirm;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 待确认预案存储（内存实现 + 惰性过期清理）。
 * 接口语义与 Redis Hash + TTL 一致，后续可无缝换 Redis 实现。
 */
@Component
public class PendingOpStore {

    private final Map<String, PendingOperation> store = new ConcurrentHashMap<>();

    public PendingOperation create(String toolName, String argsJson, String summary, String riskDesc,
                                   String username, String sessionId, int ttlSeconds) {
        PendingOperation op = new PendingOperation();
        op.setOpId(UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        op.setToolName(toolName);
        op.setArgsJson(argsJson);
        op.setSummary(summary);
        op.setRiskDesc(riskDesc);
        op.setStatus(PendingOperation.Status.PENDING);
        op.setUsername(username);
        op.setSessionId(sessionId);
        op.setCreatedAt(Instant.now());
        op.setExpireAt(Instant.now().plusSeconds(ttlSeconds));
        store.put(op.getOpId(), op);
        return op;
    }

    public Optional<PendingOperation> find(String opId) {
        return Optional.ofNullable(store.get(opId)).map(op -> {
            if (op.getStatus() == PendingOperation.Status.PENDING && op.expired()) {
                op.setStatus(PendingOperation.Status.EXPIRED);
            }
            return op;
        });
    }

    /** CAS 状态迁移，返回是否成功（防双击重复确认） */
    public boolean casStatus(String opId, PendingOperation.Status expect, PendingOperation.Status target) {
        PendingOperation op = store.get(opId);
        if (op == null) {
            return false;
        }
        synchronized (op) {
            if (op.getStatus() == PendingOperation.Status.PENDING && op.expired()) {
                op.setStatus(PendingOperation.Status.EXPIRED);
                return false;
            }
            if (op.getStatus() != expect) {
                return false;
            }
            op.setStatus(target);
            return true;
        }
    }

    public void save(PendingOperation op) {
        store.put(op.getOpId(), op);
    }

    public List<PendingOperation> findByUser(String username) {
        return store.values().stream()
                .filter(op -> username.equals(op.getUsername()))
                .filter(op -> op.getStatus() == PendingOperation.Status.PENDING)
                .filter(op -> !op.expired())
                .sorted(Comparator.comparing(PendingOperation::getCreatedAt).reversed())
                .toList();
    }
}
