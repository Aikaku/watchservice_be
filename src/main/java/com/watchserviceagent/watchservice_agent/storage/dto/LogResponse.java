package com.watchserviceagent.watchservice_agent.storage.dto;

import com.watchserviceagent.watchservice_agent.storage.domain.Log;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class LogResponse {

    private Long id;
    private String eventType;
    private String path;
    private boolean exists;
    private long size;
    private long lastModifiedTime;
    private Double entropy;
    private String hash;
    private String aiLabel;
    private Double aiScore;
    private String aiDetail;
    private String collectedAt; // 프론트에서 쓰기 편하게 문자열로

    public static LogResponse from(Log log) {
        return LogResponse.builder()
                .id(log.getId())
                .eventType(log.getEventType())
                .path(log.getPath())
                .exists(log.isExists())
                .size(log.getSize())
                .lastModifiedTime(log.getLastModifiedTime())
                .entropy(log.getEntropy())
                .hash(log.getHash())
                .aiLabel(log.getAiLabel())
                .aiScore(log.getAiScore())
                .aiDetail(log.getAiDetail())
                .collectedAt(
                        log.getCollectedAt() != null
                                ? log.getCollectedAt().toString()
                                : null
                )
                .build();
    }
}
