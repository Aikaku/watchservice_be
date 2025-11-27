package com.watchserviceagent.watchservice_agent.dashboard;

import com.watchserviceagent.watchservice_agent.common.util.SessionIdManager;
import com.watchserviceagent.watchservice_agent.dashboard.dto.DashboardSummaryResponse;
import com.watchserviceagent.watchservice_agent.dashboard.dto.DashboardSummaryResponse.RecentEventSummary;
import com.watchserviceagent.watchservice_agent.storage.LogService;
import com.watchserviceagent.watchservice_agent.storage.domain.Log;
import com.watchserviceagent.watchservice_agent.watcher.WatcherRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * 메인 보드 대시보드 요약 정보를 제공하는 컨트롤러.
 *
 * GET /dashboard/summary
 */
@RestController
@RequestMapping("/dashboard")
@RequiredArgsConstructor
@Slf4j
public class DashboardController {

    private final SessionIdManager sessionIdManager;
    private final LogService logService;
    private final WatcherRepository watcherRepository;

    @GetMapping("/summary")
    public DashboardSummaryResponse getSummary() {
        String ownerKey = sessionIdManager.getSessionId();

        // 최근 로그 N개 가져오기 (LogService는 List<Log>를 반환)
        int limit = 50;
        List<Log> recentLogs = logService.getRecentLogs(ownerKey, limit);

        int dangerCount = 0;
        int warningCount = 0;

        for (Log logEntity : recentLogs) {
            String label = safeUpper(logEntity.getAiLabel());
            if ("DANGER".equals(label)) {
                dangerCount++;
            } else if ("WARNING".equals(label)) {
                warningCount++;
            }
        }

        // 보호 상태 계산 로직 (간단 버전)
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

        // 최근 이벤트의 시간 (가장 첫 번째 로그가 가장 최신이라고 가정)
        String lastEventTime = "N/A";
        if (!recentLogs.isEmpty()) {
            Log first = recentLogs.get(0);
            Instant ts = first.getCollectedAt();
            lastEventTime = (ts != null) ? ts.toString() : "N/A";
        }

        // WatcherRepository에서 현재 감시 중인 루트 경로 (없을 수도 있음)
        String watchRootPath = watcherRepository.getLastWatchedPath();

        // 대시보드용 최근 이벤트 요약 5개 정도만 잘라서 내려줌
        List<RecentEventSummary> recentEventSummaries = recentLogs.stream()
                .limit(5L)
                .map(logEntity -> {
                    Instant ts = logEntity.getCollectedAt();
                    String tsString = (ts != null) ? ts.toString() : null;
                    return RecentEventSummary.builder()
                            .id(logEntity.getId())
                            .eventType(logEntity.getEventType())
                            .path(logEntity.getPath())
                            .aiLabel(logEntity.getAiLabel())
                            .collectedAt(tsString)
                            .build();
                })
                .collect(Collectors.toList());

        return DashboardSummaryResponse.builder()
                .ownerKey(ownerKey)
                .status(status)
                .statusLabel(statusLabel)
                .lastEventTime(lastEventTime)
                .watchRootPath(watchRootPath)
                .recentDangerCount(dangerCount)
                .recentWarningCount(warningCount)
                .recentEvents(recentEventSummaries)
                .build();
    }

    private String safeUpper(String s) {
        if (s == null) return null;
        return s.toUpperCase(Locale.ROOT);
    }
}
