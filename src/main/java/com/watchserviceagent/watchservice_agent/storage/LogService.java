package com.watchserviceagent.watchservice_agent.storage;

import com.watchserviceagent.watchservice_agent.collector.dto.FileAnalysisResult;
import com.watchserviceagent.watchservice_agent.common.util.SessionIdManager;
import com.watchserviceagent.watchservice_agent.storage.domain.Log;
import com.watchserviceagent.watchservice_agent.storage.dto.LogResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 로그 저장/조회 비즈니스 로직.
 *
 * - 저장: LogWriterWorker 를 통해 비동기 INSERT
 * - 조회: LogRepository 에서 최근 로그를 가져와 LogResponse 로 변환
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LogService {

    private final SessionIdManager sessionIdManager;
    private final LogWriterWorker logWriterWorker;
    private final LogRepository logRepository;

    /**
     * FileAnalysisResult 한 건을 비동기 로그 저장 큐에 넣는다.
     */
    public void saveAsync(FileAnalysisResult result) {
        if (result == null) return;
        logWriterWorker.enqueue(result);
    }

    /**
     * 현재 ownerKey 기준으로 최근 로그를 limit 개 가져와서
     * 프론트용 LogResponse 리스트로 변환.
     */
    public List<LogResponse> getRecentLogs(int limit) {
        String ownerKey = sessionIdManager.getSessionId();
        List<Log> logs = logRepository.findRecentLogsByOwner(ownerKey, limit);
        return logs.stream()
                .map(LogResponse::from)
                .collect(Collectors.toList());
    }

    /**
     * 최근 이벤트 시각 (없으면 null).
     * 대시보드 요약 계산에 사용.
     */
    public Instant getLastEventTime() {
        List<Log> logs = logRepository.findRecentLogsByOwner(sessionIdManager.getSessionId(), 1);
        if (logs.isEmpty()) return null;
        return logs.get(0).getCollectedAt();
    }
}
