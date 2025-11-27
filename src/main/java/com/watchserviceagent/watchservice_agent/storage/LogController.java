// src/main/java/com/watchserviceagent/watchservice_agent/storage/LogController.java
package com.watchserviceagent.watchservice_agent.storage;

import com.watchserviceagent.watchservice_agent.common.util.SessionIdManager;
import com.watchserviceagent.watchservice_agent.storage.domain.Log;
import com.watchserviceagent.watchservice_agent.storage.dto.LogResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequiredArgsConstructor
@Slf4j
public class LogController {

    private final LogService logService;
    private final SessionIdManager sessionIdManager;

    /**
     * 현재 세션(에이전트)의 최근 로그 조회.
     */
    @GetMapping("/logs/recent")
    public List<LogResponse> getRecentLogs(
            @RequestParam(defaultValue = "100") int limit
    ) {
        String ownerKey = sessionIdManager.getSessionId();
        log.info("[LogController] 최근 로그 조회 요청 - ownerKey={}, limit={}", ownerKey, limit);

        List<Log> logs = logService.getRecentLogs(ownerKey, limit);
        return logs.stream()
                .map(LogResponse::from)
                .collect(Collectors.toList());
    }
}
