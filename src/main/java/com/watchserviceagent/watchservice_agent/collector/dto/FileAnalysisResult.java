package com.watchserviceagent.watchservice_agent.collector.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

/**
 * Collector가 하나의 Watcher 이벤트에 대해 생성하는 "분석 결과" DTO.
 *
 * - ownerKey: 어떤 사용자(에이전트)의 이벤트인지
 * - eventType: CREATE / MODIFY / DELETE
 * - path: 파일 절대 경로
 * - exists: 이벤트 처리 시점에 파일이 실제 존재하는지
 * - size / lastModifiedTime: 파일 메타데이터
 * - hash: SHA-256 해시 (파일이 존재하고 읽기에 성공했을 때)
 * - entropy: Shannon 엔트로피 (파일이 존재하고 읽기에 성공했을 때)
 * - collectedAt: Collector가 이 결과를 생성한 시각
 *
 * 이후 Storage(LogWriterWorker)에서 이 DTO를 큐에 받아
 * DB(Log, FileState 등)에 기록하는 용도로 사용할 수 있다.
 */
@Data
@Builder
public class FileAnalysisResult {

    /** 세션/사용자 식별자 (SessionIdManager에서 생성한 UUID) */
    private String ownerKey;

    /** 이벤트 타입 (CREATE / MODIFY / DELETE 등) */
    private String eventType;

    /** 파일의 절대 경로 문자열 */
    private String path;

    /** Collector가 분석을 수행한 시점 */
    private Instant collectedAt;

    /** 파일이 실제로 존재하는지 여부 (DELETE 이벤트의 경우 false 가능) */
    private boolean exists;

    /** 파일 크기 (바이트). 존재하지 않으면 0 또는 -1 */
    private long size;

    /** 마지막 수정 시간 (epoch millis). 존재하지 않으면 0 또는 -1 */
    private long lastModifiedTime;

    /** 파일 SHA-256 해시 (존재하고 읽기에 성공했을 때) */
    private String hash;

    /** Shannon 엔트로피 (존재하고 읽기에 성공했을 때) */
    private Double entropy;
}
