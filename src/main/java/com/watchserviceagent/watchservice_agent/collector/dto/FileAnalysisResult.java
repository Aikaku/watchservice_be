package com.watchserviceagent.watchservice_agent.collector.dto;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

import java.time.Instant;

/**
 * 파일 시스템 이벤트 1건에 대해 Collector 가 계산한
 * "변경 전/후 파일 상태"를 담는 DTO.
 *
 * 이 객체는 나중에:
 * - 로그 저장(Storage)
 * - 윈도우 단위 피처 집계(EventWindowAggregator)
 * 에서 공통으로 사용된다.
 */
@Getter
@Builder
@ToString
public class FileAnalysisResult {

    /**
     * 세션/사용자 구분용 ownerKey.
     */
    private final String ownerKey;

    /**
     * 이벤트 타입: CREATE / MODIFY / DELETE
     */
    private final String eventType;

    /**
     * 대상 파일의 절대 경로 문자열.
     */
    private final String path;

    /**
     * Collector 기준 이벤트 처리 시각.
     */
    private final Instant eventTime;

    /**
     * 변경 이전(previous) 시점에 Collector 가 알고 있던 파일의 크기 (bytes).
     * 이전 상태를 모르는 경우 null.
     */
    private final Long sizeBefore;

    /**
     * 변경 이후(current) 시점의 파일 크기 (bytes).
     * 파일이 존재하지 않으면 null.
     */
    private final Long sizeAfter;

    /**
     * 변경 이전(previous) 시점의 샘플 엔트로피 (Shannon entropy).
     * 이전 상태를 모르는 경우 null.
     */
    private final Double entropyBefore;

    /**
     * 변경 이후(current) 시점의 샘플 엔트로피 (Shannon entropy).
     * 파일이 존재하지 않으면 null.
     */
    private final Double entropyAfter;

    /**
     * 변경 이전(previous) 시점의 파일 확장자 (소문자, '.' 없이),
     * 확장자가 없으면 null.
     */
    private final String extBefore;

    /**
     * 변경 이후(current) 시점의 파일 확장자 (소문자, '.' 없이),
     * 파일이 존재하지 않거나 확장자가 없으면 null.
     */
    private final String extAfter;

    /**
     * 현재 시점에 파일이 존재하는지 여부.
     * (DELETE 이벤트 이후에는 false 일 수 있음)
     */
    private final boolean existsAfter;

    /**
     * 엔트로피 차이: entropyAfter - entropyBefore.
     * 둘 중 하나라도 null 이면 0.0 으로 처리.
     */
    public double getEntropyDiff() {
        if (entropyBefore == null || entropyAfter == null) {
            return 0.0;
        }
        return entropyAfter - entropyBefore;
    }

    /**
     * 파일 크기 차이: sizeAfter - sizeBefore.
     * 둘 중 하나라도 null 이면 0 으로 처리.
     */
    public long getSizeDiff() {
        if (sizeBefore == null || sizeAfter == null) {
            return 0L;
        }
        return sizeAfter - sizeBefore;
    }
}
