package com.watchserviceagent.watchservice_agent.storage;

import com.watchserviceagent.watchservice_agent.collector.dto.FileAnalysisResult;
import com.watchserviceagent.watchservice_agent.storage.domain.Log;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Storage 레이어의 서비스.
 *
 * 역할:
 *  - Collector 쪽에서 FileAnalysisResult를 saveAsync(...) 로 전달받아
 *    LogWriterWorker 큐에 넣는 진입점.
 *  - Controller 쪽에서 로그 조회 요청이 오면 LogRepository를 통해 조회.
 */
@Service
@RequiredArgsConstructor
public class LogService {

    private final LogWriterWorker logWriterWorker;
    private final LogRepository logRepository;

    /**
     * Collector가 생성한 분석 결과를 비동기 로그 쓰기 큐에 넣는다.
     *
     * @param result FileCollectorService에서 생성한 FileAnalysisResult
     */
    public void saveAsync(FileAnalysisResult result) {
        logWriterWorker.enqueue(result);
    }

    /**
     * 특정 ownerKey에 대해 최근 N건의 로그를 조회한다.
     *
     * @param ownerKey 사용자/세션 식별자
     * @param limit    최대 조회 개수
     * @return Log 리스트 (최신순)
     */
    public List<Log> getRecentLogs(String ownerKey, int limit) {
        return logRepository.findRecentLogsByOwner(ownerKey, limit);
    }
}
