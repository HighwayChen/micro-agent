package com.govagent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@Data
@ConfigurationProperties(prefix = "agent")
public class AgentProperties {

    /** 上下文保留的最近对话轮数 */
    private int historyRounds = 8;

    /** 单次请求内 ReAct 循环（LLM→tool→LLM）最大轮数 */
    private int maxLoopRounds = 8;

    /** tool 结果注入上下文的最大字节数 */
    private int resultMaxBytes = 4096;

    /** 写操作确认 TTL（秒） */
    private int confirmTtlSeconds = 600;

    /** 允许执行写操作的角色 */
    private List<String> writeRoles = List.of("admin");
}
