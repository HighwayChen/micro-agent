package com.govagent.confirm;

import com.govagent.tool.ToolContext;
import com.govagent.tool.ToolExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * 写操作确认执行器：预案确认后真正执行 WRITE tool。
 * 状态机：PENDING → EXECUTING → SUCCESS/FAILED；PENDING → CANCELLED/EXPIRED。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WriteOperationExecutor {

    private final PendingOpStore store;
    private final ToolExecutor toolExecutor;

    /** 确认并执行。只有预案创建人可以确认；CAS 保证幂等（防双击/重放） */
    public PendingOperation confirm(String opId, String username) {
        PendingOperation op = store.find(opId)
                .orElseThrow(() -> new IllegalArgumentException("预案不存在或已过期: " + opId));
        if (!op.getUsername().equals(username)) {
            throw new IllegalStateException("只有预案创建人可以确认执行");
        }
        if (!store.casStatus(opId, PendingOperation.Status.PENDING, PendingOperation.Status.EXECUTING)) {
            throw new IllegalStateException("当前状态不允许确认（已执行/已取消/已过期）");
        }
        ToolContext ctx = ToolContext.of(username, List.of("admin"), op.getSessionId());
        try {
            String result = toolExecutor.executeConfirmed(op.getToolName(), op.getArgsJson(), ctx);
            op.setStatus(PendingOperation.Status.SUCCESS);
            op.setResult(result);
        } catch (Exception e) {
            log.error("写操作执行失败 opId={} tool={}", opId, op.getToolName(), e);
            op.setStatus(PendingOperation.Status.FAILED);
            op.setErrorMessage(e.getMessage());
        }
        op.setFinishedAt(Instant.now());
        store.save(op);
        log.info("[confirm] opId={} {} → {} ({})", opId, op.getToolName(), op.getStatus(), username);
        return op;
    }

    /** 取消预案 */
    public PendingOperation cancel(String opId, String username) {
        PendingOperation op = store.find(opId)
                .orElseThrow(() -> new IllegalArgumentException("预案不存在或已过期: " + opId));
        if (!op.getUsername().equals(username)) {
            throw new IllegalStateException("只有预案创建人可以取消");
        }
        if (!store.casStatus(opId, PendingOperation.Status.PENDING, PendingOperation.Status.CANCELLED)) {
            throw new IllegalStateException("当前状态不允许取消");
        }
        return op;
    }
}
