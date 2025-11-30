package com.watchserviceagent.watchservice_agent.storage;

import com.watchserviceagent.watchservice_agent.collector.dto.FileAnalysisResult;
import com.watchserviceagent.watchservice_agent.storage.domain.Log;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Collector → Storage 구간의 비동기 큐 역할을 하는 워커.
 *
 * 역할:
 *  - Collector/FileCollectorService 에서 생성한 FileAnalysisResult 를 큐에 넣으면,
 *  - 별도 워커 스레드가 이를 하나씩 꺼내어 SQLite 에 Log 로 INSERT 한다.
 *
 * 특징:
 *  - Collector/Watcher 스레드에서는 DB Lock/지연을 신경 쓰지 않고 빠르게 큐에만 넣고 반환 가능.
 *  - DB 접근은 한 스레드에서 순차적으로 이루어져 락/동시성 문제를 줄인다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LogWriterWorker {

    // DB insert 를 수행하는 Repository
    private final LogRepository logRepository;

    // Collector에서 전달되는 분석 결과를 보관하는 큐
    private final BlockingQueue<FileAnalysisResult> queue = new LinkedBlockingQueue<>();

    // 워커 스레드 참조
    private Thread workerThread;

    // 종료 플래그
    private volatile boolean running = true;

    /**
     * 스프링 컨텍스트 초기화 시 워커 스레드 시작.
     */
    @PostConstruct
    public void start() {
        workerThread = new Thread(this::runWorker, "LogWriterWorker-Thread");
        workerThread.start();
        log.info("[LogWriterWorker] 워커 스레드 시작");
    }

    /**
     * 스프링 컨텍스트 종료 시 워커 스레드 종료.
     */
    @PreDestroy
    public void stop() {
        running = false;
        if (workerThread != null) {
            workerThread.interrupt();
            try {
                workerThread.join(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("[LogWriterWorker] stop() join 중 인터럽트", e);
            }
        }
        log.info("[LogWriterWorker] 워커 스레드 종료");
    }

    /**
     * Collector 가 생성한 FileAnalysisResult 를 큐에 넣는 진입점.
     */
    public void enqueue(FileAnalysisResult result) {
        if (result == null) {
            return;
        }
        try {
            // 큐가 가득 차면 대기 (현재는 무한 큐)
            queue.put(result);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[LogWriterWorker] enqueue 중 인터럽트 발생", e);
        }
    }

    /**
     * 워커 스레드 본체.
     * - running 플래그가 true인 동안 큐에서 결과를 꺼내어 DB에 저장한다.
     */
    private void runWorker() {
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                // 큐에서 다음 결과 꺼내기 (없으면 대기)
                FileAnalysisResult result = queue.take();

                // FileAnalysisResult → Log로 변환 후 저장
                Log logEntity = mapToLog(result);
                logRepository.insertLog(logEntity);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.info("[LogWriterWorker] 워커 스레드 인터럽트, 종료 준비");
            } catch (Exception e) {
                log.error("[LogWriterWorker] 로그 저장 중 예외 발생", e);
            }
        }
        log.info("[LogWriterWorker] 워커 루프 종료");
    }

    /**
     * FileAnalysisResult DTO를 Log 도메인으로 매핑.
     *
     * FileAnalysisResult 에는 "변경 이후" 기준 정보(sizeAfter, entropyAfter, existsAfter, eventTime 등)가 들어있다.
     * 여기서는 그 값을 Log 에 옮겨 담는다.
     *
     * @param r Collector 결과
     * @return Log 엔티티
     */
    private Log mapToLog(FileAnalysisResult r) {
        // Collector 기준 이벤트 처리 시각을 collectedAt 으로 사용
        Instant collectedAt = r.getEventTime();
        if (collectedAt == null) {
            collectedAt = Instant.now();
        }

        // sizeAfter / entropyAfter 사용 (null 방지 처리)
        Long sizeAfter = r.getSizeAfter();
        Double entropyAfter = r.getEntropyAfter();
        boolean existsAfter = r.isExistsAfter();

        long sizeForLog = (sizeAfter != null) ? sizeAfter : -1L;

        // lastModifiedTime 은 아직 별도 필드가 없으므로,
        // 우선 이벤트 발생 시각을 대용으로 사용 (추후 Collector 확장 시 수정 가능)
        long lastModifiedForLog = collectedAt.toEpochMilli();

        return Log.builder()
                .ownerKey(r.getOwnerKey())
                .eventType(r.getEventType())
                .path(r.getPath())
                .exists(existsAfter)
                .size(sizeForLog)
                .lastModifiedTime(lastModifiedForLog)
                .hash(null)              // hash 계산은 아직 안 하므로 null
                .entropy(entropyAfter)   // 변경 이후 기준 엔트로피
                .aiLabel(null)           // AI 결과는 아직 없으므로 null
                .aiScore(null)
                .aiDetail(null)
                .collectedAt(collectedAt)
                .build();
    }
}
