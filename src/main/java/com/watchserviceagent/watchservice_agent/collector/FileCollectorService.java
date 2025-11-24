package com.watchserviceagent.watchservice_agent.collector;

import com.watchserviceagent.watchservice_agent.collector.business.EntropyAnalyzer;
import com.watchserviceagent.watchservice_agent.collector.business.HashCalculator;
import com.watchserviceagent.watchservice_agent.collector.dto.FileAnalysisResult;
import com.watchserviceagent.watchservice_agent.storage.LogService; // ✅ Storage 연동
import com.watchserviceagent.watchservice_agent.watcher.dto.WatcherEventRecord;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;

/**
 * Watcher에서 발생한 파일/폴더 이벤트를 받아
 * 실제 파일 내용을 분석(해시/엔트로피)하고,
 * 그 결과를 Storage/AI로 넘길 수 있는 형태(FileAnalysisResult)로 구성하는 서비스.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileCollectorService {

    private final HashCalculator hashCalculator;
    private final EntropyAnalyzer entropyAnalyzer;
    private final LogService logService;   // ✅ Storage(LogWriterWorker)를 사용하기 위한 의존성

    /**
     * Watcher에서 들어온 이벤트 하나를 처리한다.
     *
     * @param record WatcherEventRecord (ownerKey, eventType, path, timestamp)
     */
    public void handleWatchEvent(WatcherEventRecord record) {
        if (record == null) {
            log.warn("[FileCollector] null record가 전달되었습니다.");
            return;
        }

        Path path = Paths.get(record.getPath());
        String eventType = record.getEventType();
        String ownerKey = record.getOwnerKey();
        Instant eventTime = record.getTimestamp();

        FileAnalysisResult result = analyzePath(ownerKey, eventType, path, eventTime);

        // ✅ Storage 큐에 비동기 저장 요청
        logService.saveAsync(result);

        // 디버깅용 로그
        log.info("[FileCollector] 분석 결과 enqueue - ownerKey={}, eventType={}, path={}, exists={}, size={}, hash={}, entropy={}",
                result.getOwnerKey(),
                result.getEventType(),
                result.getPath(),
                result.isExists(),
                result.getSize(),
                result.getHash(),
                result.getEntropy()
        );
    }

    /**
     * ✅ 폴더 등록 시 "초기 baseline"을 기록하기 위한 메서드.
     *
     * - Watcher 이벤트 없이도, 지정된 파일 경로에 대해
     *   eventType = "INITIAL" 로 FileAnalysisResult를 생성하여 DB에 저장한다.
     *
     * @param ownerKey 세션/사용자 식별자
     * @param path     초기 상태를 기록할 파일 경로
     */
    public void collectInitialBaseline(String ownerKey, Path path) {
        if (ownerKey == null || path == null) {
            log.warn("[FileCollector] collectInitialBaseline - ownerKey 또는 path가 null입니다. ownerKey={}, path={}", ownerKey, path);
            return;
        }

        // INITIAL 이벤트는 Watcher 이벤트가 아니라 baseline 등록이므로
        // eventTime은 지금 시간(Instant.now())를 사용한다.
        Instant now = Instant.now();

        FileAnalysisResult result = analyzePath(ownerKey, "INITIAL", path, now);

        // baseline도 일반 이벤트와 동일하게 비동기로 저장
        logService.saveAsync(result);

        log.info("[FileCollector] 초기 baseline 기록 - ownerKey={}, path={}, size={}, hash={}, entropy={}",
                result.getOwnerKey(),
                result.getPath(),
                result.getSize(),
                result.getHash(),
                result.getEntropy()
        );
    }

    /**
     * 실제 파일 시스템 상의 경로에 대해 메타데이터 + 해시 + 엔트로피 분석을 수행한다.
     */
    private FileAnalysisResult analyzePath(String ownerKey,
                                           String eventType,
                                           Path path,
                                           Instant eventTime) {

        boolean exists = Files.exists(path);
        long size = -1L;
        long lastModified = -1L;

        if (exists && Files.isRegularFile(path)) {
            try {
                BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
                size = attrs.size();
                lastModified = attrs.lastModifiedTime().toMillis();
            } catch (IOException e) {
                log.warn("[FileCollector] 파일 메타데이터 조회 중 예외 발생: {}", path, e);
            }
        }

        String hash = null;
        Double entropy = null;

        if (exists && Files.isRegularFile(path)) {
            hash = hashCalculator.calculateSha256(path);
            entropy = entropyAnalyzer.calculateShannonEntropy(path);
        }

        return FileAnalysisResult.builder()
                .ownerKey(ownerKey)
                .eventType(eventType)
                .path(path.toAbsolutePath().normalize().toString())
                .collectedAt(Instant.now())  // Collector 실행 시각
                .exists(exists)
                .size(size)
                .lastModifiedTime(lastModified)
                .hash(hash)
                .entropy(entropy)
                .build();
    }
}
