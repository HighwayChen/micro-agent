# gov-agent-platform

服务治理场景的多 Agent 聊天机器人平台。一个聊天入口，LLM 意图路由到四个领域 Agent；
治理系统的调用（tool 层）全部内置 mock 数据，**克隆即跑**，配置任意 OpenAI 兼容网关即可演示。

## 架构

```
┌──────────┐   POST /api/chat (SSE)   ┌─────────────────────────────────────────────┐
│  聊天页   │ ───────────────────────▶ │  AgentChatService（编排）                    │
│ index.html│ ◀─────────────────────── │                                             │
└──────────┘   token/tool_call/...     │  LlmRouter ──▶ AgentRegistry                │
                                      │   （意图路由）     ├ faq        ─ search_faq │
                                      │                   ├ governance ─ 元数据/拓扑 │
                                      │                   ├ diagnosis  ─ ReAct 多步  │
                                      │                   ├ vi         ─ dump/JVM    │
                                      │                   └ general    ─ 兜底        │
                                      │                                             │
                                      │  ToolRegistry（注解→OpenAI function schema）│
                                      │  ToolExecutor ── READ 直接执行               │
                                      │              └── WRITE 拦截 ─▶ PendingOpStore│
                                      │                                    （人工确认）│
                                      │  OpenAiClient（手写 OpenAI 兼容协议：         │
                                      │    /v1/chat/completions + tools + SSE）      │
                                      └─────────────────────────────────────────────┘
```

核心设计点：

- **手写 OpenAI 兼容客户端**：JDK17 + Boot3，不依赖任何 AI SDK；`StreamAggregator` 处理流式协议中最麻烦的部分——`delta.tool_calls` 按 index 分片到达，需增量拼接 arguments 才能得到完整调用参数
- **注解驱动 Tool 体系**：`@AiTool`/`@AiToolParam` 反射生成 function JSON Schema；绑定方法统一签名，`ToolContext` 不暴露给 LLM
- **写操作人工确认状态机**：WRITE tool 永不在对话链路直接执行，而是生成预案（Redis 语义的 CAS + TTL），用户在聊天页点确认后才执行，回执可被 Agent 查询
- **Thread dump 大文本预处理**：几十万字符的 dump 先按调用栈签名聚合成 top-N 热点线程组，再喂给 LLM
- **新增 Agent 零路由代码**：注册 AgentDefinition 即可，路由 LLM 自动感知其 description

## 快速开始

要求 JDK 17 + Maven 3.6+。

```bash
# 配置任意 OpenAI 兼容网关（one-api / vLLM / DashScope / DeepSeek 均可）
export AGENT_LLM_BASE_URL=https://your-gateway.example.com
export AGENT_LLM_API_KEY=sk-xxx
export AGENT_LLM_MODEL=deepseek-chat          # 任意模型
# export AGENT_LLM_ROUTER_MODEL=cheap-model   # 可选：路由用便宜模型

mvn spring-boot:run
# 打开 http://localhost:8080
```

> 不配置 LLM 也能启动：页面可访问，对话会返回友好降级提示。

## 内置演示剧本（mock 数据）

一条完整的「订单服务超时」根因链，建议按顺序体验：

| 问题 | 命中 Agent | 看点 |
|---|---|---|
| `订单服务为什么超时？` | 故障诊断 | ReAct 多步：告警→拓扑→实例健康→配置变更→根因（timeout_ms 3000→500）→建议摘除 10.2.3.4 |
| `库存服务 prod 有哪些实例？` | 治理数据 | 表格化数据查询 |
| `分析一下库存服务的线程 dump` | 实例内省 | dump 聚合出 4 个同栈热点线程 + 连接池等待 |
| `Apollo 配置发布后多久生效？` | FAQ | RAG 检索（MockRagClient） |
| `把库存服务 prod 的 10.2.3.4 摘除流量` | 故障诊断/VI | **写操作确认卡片**：确认后 mock 数据真实变更（实例变 PULLED_OUT） |
| `刚才的摘除执行成功了吗？` | 故障诊断 | get_operation_result 查询回执 |
| `帮库存服务把 soa.rpc.timeout_ms 改回 3000` | 故障诊断/治理 | Apollo 配置变更预案 |

## SSE 事件协议

`POST /api/chat`，body `{"message":"...","sessionId":"可选"}`，返回 `text/event-stream`：

| 事件 | 说明 |
|---|---|
| `message_start` | 路由结果（agentId/agentName） |
| `token` | 文本增量 |
| `tool_call` | LLM 发起工具调用（前端展示「正在查询…」） |
| `tool_result` | 工具结果摘要 + 耗时 |
| `confirm_required` | 写操作预案（opId/summary/riskDesc/expireAt），前端渲染确认卡片 |
| `error` | 错误（流不中断） |
| `done` | messageId / finishReason / loops |

其他接口：`GET /api/sessions`、`GET /api/sessions/{id}/messages`、`POST /api/feedback`、
`GET /api/confirm/list`、`POST /api/confirm/{opId}`、`DELETE /api/confirm/{opId}`、`GET /api/agents`。

## 配置

```yaml
agent:
  llm:
    base-url / api-key / model / router-model / temperature / max-tokens / enabled
  history-rounds: 8        # 上下文保留轮数
  max-loop-rounds: 8       # 单次对话 ReAct 最大轮数
  result-max-bytes: 4096   # tool 结果注入上下文上限
  confirm-ttl-seconds: 600 # 写操作预案 TTL
  write-roles: admin       # 允许写操作的角色
```

## 接入真实治理系统

tool 层是唯一需要替换的部分，绑定类在 `com.govagent.tool.binding`：

1. 每个绑定类对应一个治理系统（MetadataTools→元数据中心/RMS、TopologyTools→依赖分析、
   HealthTools→扁鹊+监控、OplogTools→oplog+Apollo、ViTools→VI 实例内省端点）
2. 方法内把 `mockData.get("xxx")` 换成真实 HTTP/Feign 调用，**方法签名与注解保持不变**，schema 自动不变
3. `RagClient` 接口换成真实 LangChain RAG 服务的 WebClient 实现（契约：`POST /rag/query {question, topK} → {answer, sources}`）
4. `PendingOpStore`/`ChatSessionService` 换 Redis/MySQL 实现（当前内存实现，接口语义一致）

## 测试

```bash
mvn test   # 29 个测试：协议分片聚合 / schema 生成 / 读写拦截 / 确认状态机 / 端到端 SSE（MockWebServer 模拟 LLM）
```

## 目录导览

```
com.govagent
├── llm/           OpenAiClient（协议）、StreamAggregator（tool_calls 分片聚合）、model/
├── router/        LlmRouter（意图路由：命中/澄清/兜底）
├── agent/         AgentRegistry（faq/governance/diagnosis/vi/general）
├── tool/          anno（@AiTool）、ToolRegistry/SchemaGenerator/Executor、binding（mock 绑定）
├── confirm/       PendingOperation、PendingOpStore（CAS+TTL）、WriteOperationExecutor
├── rag/           RagClient 接口 + Mock 实现
├── conversation/  ContextTrimmer（多轮裁剪）
├── chat/          AgentChatService（ReAct 编排）、会话模型
├── sse/           ChatEvent
└── web/           ChatController（SSE）、ConfirmController、SessionController
resources
├── static/index.html        内置聊天页（无 CDN 依赖，含确认卡片/工具过程折叠/markdown）
└── mockdata/                剧本数据（订单超时根因链）
```
