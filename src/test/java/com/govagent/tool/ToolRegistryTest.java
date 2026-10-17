package com.govagent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.govagent.config.AgentProperties;
import com.govagent.confirm.PendingOpStore;
import com.govagent.tool.binding.MetadataTools;
import com.govagent.tool.binding.WriteTools;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolRegistryTest {

    private ToolRegistry registry;
    private ToolExecutor executor;
    private PendingOpStore pendingOpStore;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper();
        ToolSchemaGenerator generator = new ToolSchemaGenerator();
        MetadataTools metadataTools = new MetadataTools();
        WriteTools writeTools = new WriteTools();
        com.govagent.tool.mock.MockDataService mockDataService =
                new com.govagent.tool.mock.MockDataService(mapper);
        ReflectionTestUtils.setField(metadataTools, "mockData", mockDataService);
        ReflectionTestUtils.setField(metadataTools, "objectMapper", mapper);
        ReflectionTestUtils.setField(writeTools, "mockData", mockDataService);
        ReflectionTestUtils.setField(writeTools, "objectMapper", mapper);

        registry = new ToolRegistry(List.of(metadataTools, writeTools), generator);
        registry.init();
        pendingOpStore = new PendingOpStore();
        AgentProperties properties = new AgentProperties();
        executor = new ToolExecutor(registry, pendingOpStore, new AuditLog(), properties, mapper);
    }

    @Test
    void schemaContainsPropertiesAndRequired() {
        ToolDescriptor descriptor = registry.require("get_service_instances");
        String schema = descriptor.getDefinition().getFunction().getParameters().toString();

        assertThat(schema).contains("\"serviceId\"");
        assertThat(schema).contains("\"env\"");
        assertThat(schema).contains("\"enum\"");
        assertThat(schema).contains("prod");
        // ToolContext 参数不应出现在 schema 里
        assertThat(schema).doesNotContain("ctx");
    }

    @Test
    void readOnlyToolExecutesDirectly() {
        ToolContext ctx = ToolContext.of("zhangsan", List.of("admin"), "session-1");
        ToolExecutionResult result = executor.execute("get_service_instances",
                "{\"serviceId\":\"inventory-service\",\"env\":\"prod\"}", ctx);

        assertThat(result.getType()).isEqualTo(ToolExecutionResult.Type.EXECUTED);
        assertThat(result.getResultJson()).contains("10.2.3.4");
        assertThat(result.getResultJson()).contains("UNHEALTHY");
    }

    @Test
    void writeToolIsInterceptedForConfirm() {
        ToolContext ctx = ToolContext.of("zhangsan", List.of("admin"), "session-1");
        ToolExecutionResult result = executor.execute("pull_out_instance",
                "{\"serviceId\":\"inventory-service\",\"env\":\"prod\",\"ip\":\"10.2.3.4\"}", ctx);

        assertThat(result.getType()).isEqualTo(ToolExecutionResult.Type.CONFIRM_REQUIRED);
        assertThat(result.getPendingOperation().getSummary())
                .contains("inventory-service").contains("10.2.3.4").contains("摘除");
        assertThat(result.getPendingOperation().getStatus())
                .isEqualTo(com.govagent.confirm.PendingOperation.Status.PENDING);
        // 未执行：实例状态未变
        assertThat(executor.execute("get_service_instances",
                "{\"serviceId\":\"inventory-service\",\"env\":\"prod\"}", ctx).getResultJson())
                .contains("UNHEALTHY").doesNotContain("PULLED_OUT");
    }

    @Test
    void writeToolRejectedWithoutRole() {
        ToolContext ctx = ToolContext.of("guest", List.of("viewer"), "session-1");
        ToolExecutionResult result = executor.execute("pull_out_instance",
                "{\"serviceId\":\"inventory-service\",\"env\":\"prod\",\"ip\":\"10.2.3.4\"}", ctx);

        assertThat(result.getType()).isEqualTo(ToolExecutionResult.Type.ERROR);
        assertThat(result.getErrorMessage()).contains("无写操作权限");
    }

    @Test
    void unknownToolReturnsError() {
        ToolContext ctx = ToolContext.of("zhangsan", List.of("admin"), "s");
        assertThatThrownBy(() -> registry.require("no_such_tool"))
                .isInstanceOf(IllegalArgumentException.class);
        ToolExecutionResult result = executor.execute("no_such_tool", "{}", ctx);
        assertThat(result.getType()).isEqualTo(ToolExecutionResult.Type.ERROR);
    }

    @Test
    void duplicateToolNameRejected() {
        ToolSchemaGenerator generator = new ToolSchemaGenerator();
        ToolRegistry duplicate = new ToolRegistry(
                List.of(new MetadataTools(), new MetadataTools()), generator);
        assertThatThrownBy(duplicate::init)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("重复的 tool 名称");
    }
}
