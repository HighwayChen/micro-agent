package com.govagent.sse;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 聊天 SSE 事件。事件类型即 SSE event name。
 * <p>
 * 事件序列：message_start → (token | tool_call → tool_result | confirm_required)* → done
 */
@Data
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChatEvent {

    public static final String MESSAGE_START = "message_start";
    public static final String TOKEN = "token";
    public static final String TOOL_CALL = "tool_call";
    public static final String TOOL_RESULT = "tool_result";
    public static final String CONFIRM_REQUIRED = "confirm_required";
    public static final String CONFIRM_RESULT = "confirm_result";
    public static final String ERROR = "error";
    public static final String DONE = "done";

    private String type;
    private String agentId;
    private String agentName;
    private String messageId;
    /** token 事件的文本增量 */
    private String delta;
    private String toolCallId;
    private String toolName;
    private String argsPreview;
    private Boolean ok;
    private Long costMs;
    private String preview;
    private String opId;
    private String summary;
    private String riskDesc;
    private String expireAt;
    private String message;
    private String finishReason;
    private Integer loops;

    public static ChatEvent of(String type) {
        ChatEvent event = new ChatEvent();
        event.setType(type);
        return event;
    }

    public String toJson(ObjectMapper mapper) {
        try {
            return mapper.writeValueAsString(this);
        } catch (Exception e) {
            return "{\"type\":\"error\",\"message\":\"事件序列化失败\"}";
        }
    }
}
