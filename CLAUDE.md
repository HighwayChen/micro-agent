# CLAUDE.md

本文件是给 Claude Code 的项目上下文，跨机器/跨会话共享。开发前请完整阅读。

## 项目背景

服务治理场景的多 Agent 聊天机器人平台（个人项目，演示/学习用）。一个聊天入口，LLM 意图路由到四个领域
Agent；治理系统调用（tool 层）全部 mock，克隆即跑。原型来自 soa-admin-service（公司服务治理平台）的
场景抽象，但**本项目与它零依赖**。

- 远端：https://github.com/HighwayChen/micro-agent
- 架构与演示剧本详见 `README.md`（含架构图、SSE 协议、mock 替换指引）

## 环境与命令

- JDK 17 + Maven 3.6+（无数据库依赖，全部内存实现）
- 运行需要配置 OpenAI 兼容网关：`AGENT_LLM_BASE_URL` / `AGENT_LLM_API_KEY` / `AGENT_LLM_MODEL`
  （不配也能启动，对话返回降级提示）
- 命令：`mvn test`（29 个测试）/ `mvn spring-boot:run` → http://localhost:8080

## 架构速览

```
入口: static/index.html → POST /api/chat (SSE)
编排: chat/AgentChatService —— 路由 → Agent ReAct 循环（LLM⇄tool）→ SSE 事件
路由: router/LlmRouter（LLM 意图路由：命中/澄清/兜底，防幻觉校验 agentId）
Agent: agent/AgentRegistry —— faq / governance / diagnosis / vi / general(兜底)
工具: tool/anno/@AiTool+@AiToolParam → ToolRegistry 启动期反射生成 OpenAI function schema
      ToolExecutor：READ 直接执行；WRITE 拦截 → confirm/ 生成待确认预案
LLM:  llm/OpenAiClient（手写 OpenAI 兼容协议：/v1/chat/completions + tools + SSE）
      llm/StreamAggregator（delta.tool_calls 按 index 分片增量聚合——流式协议最难的部分）
确认: confirm/PendingOpStore（CAS+TTL，内存实现）→ WriteOperationExecutor 确认后执行
RAG:  rag/RagClient 接口 + MockRagClient（关键词匹配 faq.json，占位契约见接口注释）
```

mock 剧本在 `src/main/resources/mockdata/`：一条完整的「订单服务超时」根因链
（告警→拓扑→实例 10.2.3.4 异常→Apollo timeout 3000→500→发布 v2.3.1→线程 dump 热点）。

## 已定型的设计决策（不要推翻，除非用户明确要求）

1. **手写 OpenAI 客户端**，不用 spring-ai/langchain4j——刻意体现协议层深度；改模型只换配置
2. **tool 注解反射生成 schema**，绑定方法签名 `String xxx(@AiToolParam(...) 参数, ToolContext ctx)`，
   返回 JsonNode/String；ToolContext 不进 schema
3. **写操作永不直接执行**：WRITE tool → 预案（summaryTemplate 渲染摘要）→ 聊天页确认卡片 →
   CAS 幂等执行 → mock 数据真实变更（如实例状态→PULLED_OUT）→ Agent 可用 get_operation_result 查回执
4. **诊断 Agent 用单 LLM ReAct 循环**（非代码固定编排），system prompt 写排查思路 + max-loop-rounds=8 限制
5. **thread dump 先聚合再给 LLM**：按调用栈签名分组取 top-5（ViTools.aggregate），帧数截 12
6. 会话/预案存储是内存实现但接口化（ChatSessionService/PendingOpStore），换 Redis/MySQL 只动实现类
7. 聊天页 `static/index.html` 单文件、无 CDN 依赖（含手写 markdown 渲染 + 表格）

## 扩展约定

- **加 tool**：在 `tool/binding/` 新建绑定类（继承 AbstractMockBinding，实现 ToolBinding），
  方法加 `@AiTool`；无需改注册代码。写操作必须给 `riskDesc` + `summaryTemplate`
- **加 Agent**：`AgentRegistry` 加一个 AgentDefinition（id/name/description/systemPrompt/tools），
  路由 LLM 自动感知，无需改路由代码
- **SSE 事件**：改动事件协议时同步改 `sse/ChatEvent` 与 index.html 的 switch 分支

## 待办路线图（按优先级）

1. 接入用户自建的 LangChain RAG 服务（契约：POST /rag/query {question, topK} → {answer, sources}；
   只需替换 MockRagClient）
2. 真实治理系统对接：binding 类里 mockData.get() 换成真实 HTTP 调用（签名/注解不动）
3. ChatSessionService/PendingOpStore 换 Redis/MySQL 持久化实现
4. 诊断 Agent 的 plan-execute 编排（二期优化，当前 ReAct 已够用）
5. 可选：上下文摘要压缩（当前 ContextTrimmer 只按轮数截断）

## 其他

- demo 单用户模式：X-Username 头（默认 demo），角色固定 admin；权限体系是占位
- 测试基线：`mvn test` 必须全绿后再提交；e2e 测试（ChatEndToEndTest）用 MockWebServer 模拟 LLM
- git 提交信息用中文，一句话说清改动
