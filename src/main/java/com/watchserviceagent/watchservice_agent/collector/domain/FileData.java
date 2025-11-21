package com.watchserviceagent.watchservice_agent.collector.domain;

import lombok.Builder;
import lombok.Data;

import java.nio.file.Path;

/**
 * Collector가 파일 시스템에서 직접 읽어온 "기본 메타데이터"를 담는 도메인 객체.
 *
 * - 파일의 존재 여부, 디렉터리 여부
 * - 사이즈, 마지막 수정 시각 등
 * - 실질적인 해시/엔트로피 계산 결과는 FileAnalysisResult에 포함된다.
 */
@Data
@Builder
public class FileData {

    /**
     * 파일의 절대 경로.
     */
    private Path path;

    /**
     * 파일이 현재 실제로 존재하는지 여부.
     * - DELETE 이벤트의 경우 false일 수 있음.
     */
    private boolean exists;

    /**
     * 디렉터리인지 여부.
     */
    private boolean directory;

    /**
     * 파일 크기 (바이트 단위).
     * - 디렉터리거나 존재하지 않는 경우 0 또는 -1로 둘 수 있다.
     */
    private long size;

    /**
     * 마지막 수정 시각 (epoch millis).
     * - 존재하지 않는 경우 0 또는 -1로 둘 수 있다.
     */
    private long lastModifiedTime;
}
