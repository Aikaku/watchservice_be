package com.watchserviceagent.watchservice_agent.watcher.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.Instant;

/**
 * Collector(그리고 나중에는 Storage/AI)로 전달할 이벤트 데이터 DTO.
 *
 * - Watcher 레이어에서 "이 사용자 / 어떤 이벤트 / 어떤 경로 / 언제" 를
 *   한 번에 담아서 넘길 때 사용한다.
 */
@Data
@AllArgsConstructor
public class WatcherEventRecord {

    /**
     * 사용자 세션 키 (UUID).
     *  - SessionIdManager에서 생성/관리하는 ownerKey.
     */
    private String ownerKey;

    /**
     * 이벤트 타입 문자열.
     *  - CREATE / MODIFY / DELETE
     */
    private String eventType;

    /**
     * 이벤트가 발생한 파일/폴더의 절대 경로 문자열.
     */
    private String path;

    /**
     * 이벤트 발생(감지) 시각.
     */
    private Instant timestamp;
}
