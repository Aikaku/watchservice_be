package com.watchserviceagent.watchservice_agent.storage.domain;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

import java.time.Instant;

/**
 * SQLite log 테이블과 1:1 매핑되는 도메인 객체.
 */
@Getter
@Builder
@ToString
public class Log {

    private final Long id;               // AUTOINCREMENT PK

    private final String ownerKey;       // 설치/에이전트 식별자
    private final String eventType;      // CREATE / MODIFY / DELETE
    private final String path;           // 파일 경로 (절대 경로)

    private final boolean exists;        // 이벤트 이후 파일 존재 여부
    private final long size;             // 이벤트 이후 파일 크기 (없으면 -1 등)
    private final long lastModifiedTime; // 이벤트 시점의 lastModifiedTime (epoch ms)
    private final String hash;           // 파일 해시 (현재는 null일 수도 있음)
    private final Double entropy;        // 이벤트 이후 엔트로피

    private final String aiLabel;        // AI 라벨 (SAFE / WARNING / DANGER 등)
    private final Double aiScore;        // AI 점수 (0.0~1.0)
    private final String aiDetail;       // AI 상세 설명

    private final Instant collectedAt;   // 로그 수집 시각 (이벤트 시간)
}
