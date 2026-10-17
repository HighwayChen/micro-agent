package com.govagent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "agent.llm")
public class OpenAiProperties {

    private boolean enabled = true;

    private String baseUrl = "https://api.openai.com";

    private String apiKey = "";

    private String model = "gpt-4o-mini";

    /** 意图路由模型，留空复用 model */
    private String routerModel = "";

    private double temperature = 0.2;

    private Integer maxTokens = 4096;

    private int connectTimeoutMs = 10_000;

    private int readTimeoutMs = 300_000;
}
