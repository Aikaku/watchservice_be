package com.watchserviceagent.watchservice_agent.settings.exceptionrule.dto;

import lombok.Getter;
import lombok.ToString;

/**
 * 예외 규칙 생성 요청 DTO.
 */
@Getter
@ToString
public class ExceptionRuleRequest {

    /**
     * 예외 유형: "PATH" 또는 "EXT"
     */
    private String type;

    /**
     * 예외 패턴:
     *  - PATH: 전체 경로 prefix (예: /Users/foo/Downloads)
     *  - EXT : 파일 확장자 (예: .log, .tmp)
     */
    private String pattern;

    /**
     * 사용자 메모 (선택)
     */
    private String memo;
}
