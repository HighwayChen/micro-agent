package com.govagent.web;

import com.govagent.confirm.PendingOperation;
import com.govagent.confirm.PendingOpStore;
import com.govagent.confirm.WriteOperationExecutor;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 写操作预案：列表 / 确认执行 / 取消。
 */
@RestController
@RequestMapping("/api/confirm")
@RequiredArgsConstructor
public class ConfirmController {

    private final PendingOpStore pendingOpStore;
    private final WriteOperationExecutor writeOperationExecutor;

    @GetMapping("/list")
    public List<Map<String, Object>> list(@RequestParam(defaultValue = "demo") String username) {
        return pendingOpStore.findByUser(username).stream()
                .map(op -> Map.<String, Object>of(
                        "opId", op.getOpId(),
                        "toolName", op.getToolName(),
                        "summary", op.getSummary(),
                        "riskDesc", op.getRiskDesc(),
                        "status", op.getStatus().name(),
                        "expireAt", op.getExpireAt().toString()))
                .toList();
    }

    @PostMapping("/{opId}")
    public Map<String, Object> confirm(@PathVariable String opId,
                                       @RequestHeader(value = "X-Username", defaultValue = "demo") String username) {
        PendingOperation op = writeOperationExecutor.confirm(opId, username);
        return Map.of(
                "opId", op.getOpId(),
                "status", op.getStatus().name(),
                "summary", op.getSummary(),
                "result", op.getResult() == null ? "" : op.getResult(),
                "error", op.getErrorMessage() == null ? "" : op.getErrorMessage());
    }

    @DeleteMapping("/{opId}")
    public Map<String, Object> cancel(@PathVariable String opId,
                                      @RequestHeader(value = "X-Username", defaultValue = "demo") String username) {
        PendingOperation op = writeOperationExecutor.cancel(opId, username);
        return Map.of("opId", op.getOpId(), "status", op.getStatus().name());
    }
}
