package com.watchserviceagent.watchservice_agent.dashboard.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * 메인 보드에서 사용하는 요약 정보 응답 DTO.
 *
 * status:
 *   - SAFE
 *   - WARNING
 *   - DANGER
 */
@Getter
@Builder
public class DashboardSummaryResponse {

    /**
     * 이 에이전트(설치) 식별용 ownerKey.
     */
    private String ownerKey;

    /**
     * 상태 코드 (SAFE / WARNING / DANGER)
     */
    private String status;

    /**
     * 상태 라벨 (예: "안전", "주의", "위험")
     */
    private String statusLabel;

    /**
     * 최근 이벤트 기준 시각 (ISO-8601 문자열 또는 "N/A")
     */
    private String lastEventTime;

    /**
     * 현재 감시 중인 루트 경로 (없으면 null)
     */
    private String watchRootPath;

    /**
     * 최근 24시간 기준 위험 알림 개수 (DANGER)
     * 지금은 recentLogs 내에서 계산한 값 (엄밀한 24시간은 나중에 개선)
     */
    private int recentDangerCount;

    /**
     * 최근 24시간 기준 주의 알림 개수 (WARNING)
     */
    private int recentWarningCount;

    /**
     * 대시보드에 같이 보여줄 최근 이벤트 간단 목록 (옵션)
     */
    private List<RecentEventSummary> recentEvents;

    @Getter
    @Builder
    public static class RecentEventSummary {
        private Long id;
        private String eventType;
        private String path;
        private String aiLabel;
        private String collectedAt;
    }
}
