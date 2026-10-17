package com.govagent.confirm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.govagent.config.AgentProperties;
import com.govagent.tool.AuditLog;
import com.govagent.tool.ToolContext;
import com.govagent.tool.ToolExecutionResult;
import com.govagent.tool.ToolExecutor;
import com.govagent.tool.ToolRegistry;
import com.govagent.tool.ToolSchemaGenerator;
import com.govagent.tool.binding.MetadataTools;
import com.govagent.tool.binding.WriteTools;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 写操作全链路：拦截生成预案 → 确认（CAS 幂等）→ 真正执行 → mock 数据状态变更。
 */
class ConfirmFlowTest {

    private ToolExecutor executor;
    private PendingOpStore store;
    private WriteOperationExecutor writeExecutor;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        ToolSchemaGenerator generator = new ToolSchemaGenerator();
        MetadataTools metadataTools = new MetadataTools();
        WriteTools writeTools = new WriteTools();
        com.govagent.tool.mock.MockDataService mockDataService =
                new com.govagent.tool.mock.MockDataService(mapper);
        ReflectionTestUtils.setField(metadataTools, "mockData", mockDataService);
        ReflectionTestUtils.setField(metadataTools, "objectMapper", mapper);
        ReflectionTestUtils.setField(writeTools, "mockData", mockDataService);
        ReflectionTestUtils.setField(writeTools, "objectMapper", mapper);

        ToolRegistry registry = new ToolRegistry(List.of(metadataTools, writeTools), generator);
        registry.init();
        store = new PendingOpStore();
        AgentProperties properties = new AgentProperties();
        executor = new ToolExecutor(registry, store, new AuditLog(), properties, mapper);
        writeExecutor = new WriteOperationExecutor(store, executor);
    }

    private ToolContext admin() {
        return ToolContext.of("zhangsan", List.of("admin"), "session-1");
    }

    @Test
    void fullFlow_proposalConfirmExecute_receiptVisible() throws Exception {
        // 1. LLM 发起写操作 → 被拦截为预案
        ToolExecutionResult intercepted = executor.execute("pull_out_instance",
                "{\"serviceId\":\"inventory-service\",\"env\":\"prod\",\"ip\":\"10.2.3.4\"}", admin());
        assertThat(intercepted.getType()).isEqualTo(ToolExecutionResult.Type.CONFIRM_REQUIRED);
        String opId = intercepted.getPendingOperation().getOpId();

        // 2. 用户确认 → 真正执行
        PendingOperation executed = writeExecutor.confirm(opId, "zhangsan");
        assertThat(executed.getStatus()).isEqualTo(PendingOperation.Status.SUCCESS);
        assertThat(executed.getResult()).contains("摘除流量成功");

        // 3. mock 数据状态真实变更
        String instances = executor.execute("get_service_instances",
                "{\"serviceId\":\"inventory-service\",\"env\":\"prod\"}", admin()).getResultJson();
        assertThat(instances).contains("PULLED_OUT");
        assertThat(instances).doesNotContain("UNHEALTHY");

        // 4. 回执可查询（Agent 回答「执行得怎么样」的数据源）
        PendingOperation reloaded = store.find(opId).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(PendingOperation.Status.SUCCESS);
    }

    @Test
    void doubleConfirmRejectedByCas() throws Exception {
        ToolExecutionResult intercepted = executor.execute("pull_out_instance",
                "{\"serviceId\":\"inventory-service\",\"env\":\"prod\",\"ip\":\"10.2.3.5\"}", admin());
        String opId = intercepted.getPendingOperation().getOpId();

        writeExecutor.confirm(opId, "zhangsan");
        // 双击/重放：状态已不是 PENDING，CAS 拒绝
        assertThatThrownBy(() -> writeExecutor.confirm(opId, "zhangsan"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不允许确认");
    }

    @Test
    void onlyCreatorCanConfirm() {
        ToolExecutionResult intercepted = executor.execute("pull_out_instance",
                "{\"serviceId\":\"inventory-service\",\"env\":\"prod\",\"ip\":\"10.2.3.6\"}", admin());
        String opId = intercepted.getPendingOperation().getOpId();

        assertThatThrownBy(() -> writeExecutor.confirm(opId, "someoneelse"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("创建人");
    }

    @Test
    void cancelMovesToCancelled() {
        ToolExecutionResult intercepted = executor.execute("update_apollo_config",
                "{\"serviceId\":\"inventory-service\",\"namespace\":\"SOA.RPC\",\"key\":\"soa.rpc.timeout_ms\",\"value\":\"3000\"}",
                admin());
        String opId = intercepted.getPendingOperation().getOpId();

        PendingOperation cancelled = writeExecutor.cancel(opId, "zhangsan");
        assertThat(cancelled.getStatus()).isEqualTo(PendingOperation.Status.CANCELLED);

        // 取消后不能再确认
        assertThatThrownBy(() -> writeExecutor.confirm(opId, "zhangsan"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void expiredProposalCannotConfirm() {
        ToolExecutionResult intercepted = executor.execute("pull_out_instance",
                "{\"serviceId\":\"inventory-service\",\"env\":\"prod\",\"ip\":\"10.2.3.4\"}", admin());
        PendingOperation op = intercepted.getPendingOperation();
        op.setExpireAt(java.time.Instant.now().minusSeconds(1)); // 人工使其过期

        assertThatThrownBy(() -> writeExecutor.confirm(op.getOpId(), "zhangsan"))
                .isInstanceOf(IllegalStateException.class);
    }
}
