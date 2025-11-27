package com.watchserviceagent.watchservice_agent.settings.exceptionrule.domain;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

/**
 * 파일 감시 예외(화이트리스트) 규칙 도메인.
 *
 * type:
 *   - "PATH" : 특정 경로(파일/폴더) prefix 기준 예외
 *   - "EXT"  : 확장자 기준 예외 (예: ".log", ".tmp")
 */
@Data
@Builder
public class ExceptionRule {

    private Long id;
    private String ownerKey;
    private String type;
    private String pattern;
    private String memo;
    private Instant createdAt;
}
