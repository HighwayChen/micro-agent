package com.govagent.web.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class FeedbackRequest {

    @NotNull
    private String messageId;

    /** 1 有用 / -1 无用 */
    @NotNull
    private Integer score;
}
