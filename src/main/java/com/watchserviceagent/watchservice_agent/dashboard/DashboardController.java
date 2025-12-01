package com.watchserviceagent.watchservice_agent.dashboard;

import com.watchserviceagent.watchservice_agent.common.util.SessionIdManager;
import com.watchserviceagent.watchservice_agent.dashboard.dto.DashboardSummaryResponse;
import com.watchserviceagent.watchservice_agent.storage.LogService;
import com.watchserviceagent.watchservice_agent.storage.dto.LogResponse;
import com.watchserviceagent.watchservice_agent.watcher.WatcherService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 메인 대시보드 요약 정보를 제공하는 컨트롤러.
 */
@RestController
@RequestMapping("/dashboard")
@RequiredArgsConstructor
@Slf4j
public class DashboardController {

    private final LogService logService;
    private final SessionIdManager sessionIdManager;
    private final WatcherService watcherService; // 감시 상태/경로 파악용 (필요 시)

    private static final DateTimeFormatter DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                    .withZone(ZoneId.systemDefault());

    /**
     * GET /dashboard/summary
     *
     * - 최근 50개 로그를 기반으로
     *   · DANGER / WARNING 개수 파악
     *   · 가장 최근 이벤트 시각
     *   · 상태(status) 결정
     */
    @GetMapping("/summary")
    public DashboardSummaryResponse getSummary() {
        int recentLimit = 50;
        List<LogResponse> logs = logService.getRecentLogs(recentLimit);

        int dangerCount = 0;
        int warningCount = 0;
        for (LogResponse log : logs) {
            String label = log.getAiLabel();
            if (label == null) continue;
            switch (label.toUpperCase()) {
                case "DANGER" -> dangerCount++;
                case "WARNING" -> warningCount++;
            }
        }

        int totalCount = logs.size();

        // 상태 결정 로직:
        //  - DANGER 한 개라도 있으면 → DANGER
        //  - 그 외 WARNING 이 있으면 → WARNING
        //  - 나머지 → SAFE
        String status;
        String statusLabel;
        if (dangerCount > 0) {
            status = "DANGER";
            statusLabel = "위험";
        } else if (warningCount > 0) {
            status = "WARNING";
            statusLabel = "주의";
        } else {
            status = "SAFE";
            statusLabel = "안전";
        }

        // 마지막 이벤트 시간 문자열
        String lastEventTimeStr = "-";
        if (!logs.isEmpty()) {
            // logs 는 최신순이므로 첫 번째가 가장 최근
            Instant lastEvent = logService.getLastEventTime();
            if (lastEvent != null) {
                lastEventTimeStr = DATE_TIME_FORMATTER.format(lastEvent);
            }
        }

        // 감시 경로: 지금은 WatcherService 에서 직접 제공하는 메서드가 없으니
        // 나중에 별도 Repository/Service 로 관리할 수도 있음.
        // 일단은 placeholder 로 둔다.
        String watchedPath = "(현재 감시 경로 표시 예정)";

        DashboardSummaryResponse resp = DashboardSummaryResponse.builder()
                .status(status)
                .statusLabel(statusLabel)
                .lastEventTime(lastEventTimeStr)
                .dangerCount(dangerCount)
                .warningCount(warningCount)
                .totalCount(totalCount)
                .watchedPath(watchedPath)
                .build();

        log.info("[DashboardController] /dashboard/summary -> {}", resp);
        return resp;
    }
}
