package com.watchserviceagent.watchservice_agent.settings.exceptionrule.dto;

import com.watchserviceagent.watchservice_agent.settings.exceptionrule.domain.ExceptionRule;
import lombok.Builder;
import lombok.Getter;

/**
 * 예외 규칙 조회 응답 DTO.
 */
@Getter
@Builder
public class ExceptionRuleResponse {

    private Long id;
    private String type;
    private String pattern;
    private String memo;
    private String createdAt; // ISO-8601 문자열

    public static ExceptionRuleResponse from(ExceptionRule rule) {
        return ExceptionRuleResponse.builder()
                .id(rule.getId())
                .type(rule.getType())
                .pattern(rule.getPattern())
                .memo(rule.getMemo())
                .createdAt(rule.getCreatedAt() != null ? rule.getCreatedAt().toString() : null)
                .build();
    }
}
