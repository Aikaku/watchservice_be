package com.watchserviceagent.watchservice_agent.storage.domain;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

/**
 * 파일 변경/분석 이벤트 한 건을 표현하는 로그 도메인.
 *
 * - Collector(FileAnalysisResult)에서 넘어온 정보를 기반으로 생성하여
 *   SQLite log 테이블에 저장한다.
 *
 * 추후 AI 분석 결과(aiLabel, aiScore, aiDetail)를 여기에 추가 기록할 수 있다.
 */
@Data
@Builder
public class Log {

    /** DB PK (SQLite AUTOINCREMENT) */
    private Long id;

    /** 세션/사용자 식별자 (SessionIdManager의 UUID) */
    private String ownerKey;

    /** 이벤트 타입 (CREATE / MODIFY / DELETE / INITIAL 등) */
    private String eventType;

    /** 파일 절대 경로 */
    private String path;

    /** Collector 분석 시점에 파일이 존재했는지 여부 */
    private boolean exists;

    /** 파일 크기 (바이트). 존재하지 않으면 -1 등 */
    private long size;

    /** 파일 마지막 수정 시간 (epoch millis). 존재하지 않으면 -1 등 */
    private long lastModifiedTime;

    /** SHA-256 해시 (파일이 존재하고 읽기에 성공했을 때) */
    private String hash;

    /** Shannon 엔트로피 (파일이 존재하고 읽기에 성공했을 때) */
    private Double entropy;

    /** AI 결과: 라벨 (예: NORMAL / RANSOM / SUSPICIOUS 등). 아직 사용 안 하면 null 가능 */
    private String aiLabel;

    /** AI 결과: 위험도 점수 (0.0 ~ 1.0). 아직 사용 안 하면 null 가능 */
    private Double aiScore;

    /** AI 결과: 상세 설명/메시지. 아직 사용 안 하면 null 가능 */
    private String aiDetail;

    /** Collector가 이 로그를 생성한 시각 (epoch millis 기준 또는 Instant) */
    private Instant collectedAt;
}