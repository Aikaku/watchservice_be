package com.watchserviceagent.watchservice_agent.watcher.domain;

import lombok.Builder;
import lombok.Data;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/**
 * WatchService에서 감지된 파일/폴더 변경 이벤트를 표현하는 도메인 객체.
 * Collector/Storage/AI 쪽으로 넘길 때 중간 단계로 사용할 수 있다.
 */
@Data
@Builder
public class WatcherEvent {

    /**
     * 이벤트 타입 (CREATE / MODIFY / DELETE)
     *  - 내부에서는 NIO의 WatchEvent.Kind를 문자열로 매핑해서 사용.
     */
    private String eventType;

    /**
     * 이벤트가 발생한 파일/폴더의 절대 경로.
     */
    private Path filePath;

    /**
     * 이벤트를 감지한 시각 (서버 기준 UTC)
     */
    private Instant detectedAt;

    /**
     * 현재 시점 기준으로 이 경로가 "폴더"인지 여부.
     * 삭제 이벤트(DELETE)인 경우, 이미 파일이 없어졌을 수 있으므로 항상 true/false가 의미 있지는 않을 수 있다.
     */
    public boolean isDirectory() {
        return filePath != null && Files.isDirectory(filePath);
    }

    /**
     * 현재 시점 기준으로 이 경로가 "일반 파일"인지 여부.
     * 마찬가지로 DELETE 이벤트의 경우 false가 나올 수 있다.
     */
    public boolean isFile() {
        return filePath != null && Files.isRegularFile(filePath);
    }
}
