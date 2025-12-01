package com.watchserviceagent.watchservice_agent.storage;

import com.watchserviceagent.watchservice_agent.storage.dto.LogResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 로그 조회용 컨트롤러.
 *
 * 프론트에서:
 *   GET /logs/recent?limit=50
 * 으로 사용한다.
 */
@RestController
@RequestMapping("/logs")
@RequiredArgsConstructor
@Slf4j
public class LogController {

    private final LogService logService;

    @GetMapping("/recent")
    public List<LogResponse> getRecentLogs(@RequestParam(name = "limit", defaultValue = "50") int limit) {
        if (limit <= 0) {
            limit = 50;
        } else if (limit > 1000) {
            limit = 1000; // 너무 큰 값 방지
        }

        List<LogResponse> logs = logService.getRecentLogs(limit);
        log.info("[LogController] /logs/recent limit={} -> {}건 반환", limit, logs.size());
        return logs;
    }
}
