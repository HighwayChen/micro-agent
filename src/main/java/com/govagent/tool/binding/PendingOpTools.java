package com.govagent.tool.binding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.govagent.confirm.PendingOperation;
import com.govagent.confirm.PendingOpStore;
import com.govagent.tool.ToolContext;
import com.govagent.tool.anno.AiTool;
import com.govagent.tool.anno.AiToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 写操作预案/回执查询：用户确认执行后，Agent 可查询执行结果（回答『执行得怎么样』）。
 */
@Component
public class PendingOpTools extends AbstractMockBinding {

    @Autowired
    private PendingOpStore pendingOpStore;

    @AiTool(name = "list_pending_operations", description = "查询当前用户待确认的写操作预案列表（含摘要、风险、过期时间）。")
    public JsonNode listPendingOperations(ToolContext ctx) {
        ArrayNode result = objectMapper.createArrayNode();
        for (PendingOperation op : pendingOpStore.findByUser(ctx.getUsername())) {
            result.add(toJson(op));
        }
        return result;
    }

    @AiTool(name = "get_operation_result", description = "查询某个写操作预案的状态与执行回执（确认后是否执行成功、结果如何）。用户询问『执行得怎么样/成功了吗』时使用。")
    public JsonNode getOperationResult(
            @AiToolParam(name = "opId", description = "预案 ID（confirm_required 事件/消息里给出的 opId）") String opId) {
        return pendingOpStore.find(opId)
                .map(this::toJson)
                .orElseGet(() -> error("预案不存在: " + opId));
    }

    private JsonNode toJson(PendingOperation op) {
        ObjectNode node = ok();
        node.put("opId", op.getOpId());
        node.put("toolName", op.getToolName());
        node.put("summary", op.getSummary());
        node.put("status", op.getStatus().name());
        node.put("createdAt", op.getCreatedAt().toString());
        if (op.getExpireAt() != null) {
            node.put("expireAt", op.getExpireAt().toString());
        }
        if (op.getResult() != null) {
            node.put("result", op.getResult());
        }
        if (op.getErrorMessage() != null) {
            node.put("error", op.getErrorMessage());
        }
        return node;
    }
}
