package com.govagent.agent;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 四个领域 Agent + 兜底 Agent 的注册中心。
 * 新增 Agent：加一个定义即可，路由 LLM 自动感知（无需改路由代码）。
 */
@Component
public class AgentRegistry {

    public static final String FAQ = "faq";
    public static final String GOVERNANCE = "governance";
    public static final String DIAGNOSIS = "diagnosis";
    public static final String VI = "vi";
    public static final String GENERAL = "general";

    private final Map<String, AgentDefinition> agents = new LinkedHashMap<>();

    public AgentRegistry() {
        register(AgentDefinition.builder()
                .id(FAQ).name("FAQ 知识库")
                .description("回答服务治理平台的使用问题：服务怎么接入、健康检查失败怎么办、Apollo 配置生效时间、权限申请、灰度发布操作、告警通知渠道等『怎么用/为什么』类咨询。")
                .systemPrompt("""
                        你是服务治理平台的 FAQ 助手。回答平台使用类问题。
                        - 必须先调用 search_faq 工具检索知识库，再基于检索结果回答
                        - 知识库没有的问题，如实说明并建议咨询平台值班，不要编造
                        - 回答简洁分步，中文""")
                .tools(List.of("search_faq"))
                .build());

        register(AgentDefinition.builder()
                .id(GOVERNANCE).name("治理数据查询")
                .description("查询治理平台的客观数据：服务列表与元数据（负责人/环境/实例数）、某服务的实例状态、依赖拓扑（谁调用它/它调用谁）、健康报告、监控指标 QPS/错误率/P99、最近发布记录、Apollo 配置变更历史、定时任务清单。")
                .systemPrompt("""
                        你是服务治理平台的数据查询助手。
                        - 用户提到的服务如果 ID 不明确（如只说『订单服务』），先调 list_services 用关键词确认服务 ID
                        - 查询结果用表格或列表清晰呈现，数值带上单位
                        - 只陈述数据，不做根因推测（那是诊断助手的工作）
                        - 数据缺失时如实说明""")
                .tools(List.of(
                        "list_services", "get_service_meta", "get_service_instances",
                        "get_service_topology", "get_health_report", "get_service_metrics",
                        "get_recent_releases", "get_recent_config_changes", "get_scheduled_tasks"))
                .build());

        register(AgentDefinition.builder()
                .id(DIAGNOSIS).name("故障诊断")
                .description("服务故障排查与根因定位：服务报错/超时/告警时，分析依赖拓扑、实例健康、监控指标、最近发布与配置变更、线程 dump、JVM 指标，输出根因结论与处置建议（含摘除异常实例、回滚配置等止血操作）。用户说『挂了/超时/报错/告警/排查/为什么慢』时选择本助手。")
                .systemPrompt("""
                        你是资深 SRE，负责服务故障的根因定位。按以下思路排查（可根据证据跳步）：
                        1. 看告警与监控指标确认故障现象（get_service_alerts / get_service_metrics）
                        2. 查依赖拓扑缩小范围（get_service_topology）：报错服务的下游最可疑
                        3. 逐个检查下游：实例健康（get_service_instances）、健康报告（get_health_report）
                        4. 对照时间线查变更证据（get_recent_releases / get_recent_config_changes）——80% 的故障源于变更
                        5. 需要深入实例时用 VI 工具（analyze_thread_dump / get_jvm_metrics / get_effective_config）
                        6. 输出结构化结论：故障现象 → 根因（附证据链）→ 处置建议（分『止血』与『根治』）
                        处置建议中的写操作（摘流量/改配置）可以直接发起调用——系统会生成操作预案，用户确认后才执行。
                        证据不足时明确说『尚无证据支持』，不要臆断。""")
                .tools(List.of(
                        "get_service_alerts", "get_service_metrics", "get_service_topology",
                        "get_service_instances", "get_health_report",
                        "get_recent_releases", "get_recent_config_changes",
                        "analyze_thread_dump", "get_jvm_metrics", "get_effective_config",
                        "get_startup_failures",
                        // 预案与回执查询
                        "get_operation_result",
                        // 止血写操作（走人工确认）
                        "pull_out_instance", "pull_in_instance", "update_apollo_config"))
                .build());

        register(AgentDefinition.builder()
                .id(VI).name("实例内省 (VI)")
                .description("深入单个服务实例的内部状态：线程 dump 聚合分析（CPU 高/超时/线程打满）、启动失败诊断（端口占用/配置加载失败）、运行时生效配置核对、JVM 指标（堆/GC/线程）、Spring 定时任务运行情况，以及实例级操作（清缓存、摘除/恢复流量）。")
                .systemPrompt("""
                        你是 JVM 与实例级诊断专家（VI 能力助手）。
                        - analyze_thread_dump 返回的是聚合后的热点线程组，重点解读 rank 靠前组的栈帧与状态
                        - 核对配置用 get_effective_config（运行时真实值），能发现实例未重启持旧配置的问题
                        - 启动失败诊断 get_startup_failures 会给出阶段、错误与修复建议
                        - 实例操作（清缓存/摘流量）属于写操作，系统会让用户确认
                        - 输出面向工程师：直接给结论与证据，再给建议""")
                .tools(List.of(
                        "analyze_thread_dump", "get_startup_failures", "get_effective_config",
                        "get_jvm_metrics", "get_scheduled_tasks",
                        "list_services", "get_service_instances",
                        "pull_out_instance", "pull_in_instance", "evict_spring_cache"))
                .build());

        register(AgentDefinition.builder()
                .id(GENERAL).name("通用助手")
                .description("兜底：寒暄、闲聊、与治理平台无关或意图不明确的问题。")
                .systemPrompt("""
                        你是服务治理平台的 AI 助手入口。当前问题不属于任何领域（FAQ/数据查询/故障诊断/实例内省）。
                        - 简短回应，介绍你能做什么：查服务数据、排查故障、深挖实例状态、回答平台使用问题
                        - 引导用户提出具体问题，例如『订单服务为什么超时』『库存服务有哪些实例』""")
                .tools(List.of())
                .build());
    }

    private void register(AgentDefinition definition) {
        agents.put(definition.getId(), definition);
    }

    public AgentDefinition get(String id) {
        AgentDefinition definition = agents.get(id);
        if (definition == null) {
            throw new IllegalArgumentException("未注册的 agent: " + id);
        }
        return definition;
    }

    public List<AgentDefinition> all() {
        return List.copyOf(agents.values());
    }
}
