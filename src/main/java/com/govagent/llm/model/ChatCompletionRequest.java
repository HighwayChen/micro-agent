package com.govagent.llm.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class ChatCompletionRequest {

    private String model;
    private List<ChatMessage> messages;
    private List<ToolDefinition> tools;
    private String toolChoice;
    private Boolean stream;
    private Double temperature;
    private Integer maxTokens;

    public static ChatCompletionRequest of(String model, List<ChatMessage> messages, List<ToolDefinition> tools) {
        return ChatCompletionRequest.builder()
                .model(model)
                .messages(messages)
                .tools(tools)
                .build();
    }
}
