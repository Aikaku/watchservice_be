package com.watchserviceagent.watchservice_agent.collector;

import com.watchserviceagent.watchservice_agent.collector.business.EntropyAnalyzer;
import com.watchserviceagent.watchservice_agent.collector.business.HashCalculator;
import com.watchserviceagent.watchservice_agent.collector.dto.FileAnalysisResult;
import com.watchserviceagent.watchservice_agent.watcher.dto.WatcherEventRecord;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;

/**
 * Watcher에서 발생한 파일/폴더 이벤트를 받아
 * 실제 파일 내용을 분석(해시/엔트로피)하고,
 * 그 결과를 Storage/AI로 넘길 수 있는 형태(FileAnalysisResult)로 구성하는 서비스.
 *
 * 현재 구현:
 *  - WatcherEventRecord를 입력으로 받아
 *  - 파일 메타데이터(size, lastModifiedTime 등) 수집
 *  - 파일이 존재하면 HashCalculator/EntropyAnalyzer를 통해
 *    SHA-256 해시 및 Shannon 엔트로피 계산
 *  - FileAnalysisResult 를 생성하여 log로 출력
 *
 * 이후 확장 방향:
 *  - LogWriterWorker(비동기 큐)에 FileAnalysisResult를 enqueue하여
 *    SQLite DB에 저장하도록 연계
 *  - AI 서비스에 전달할 Payload를 구성하는 단계로도 활용
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileCollectorService {

    private final HashCalculator hashCalculator;
    private final EntropyAnalyzer entropyAnalyzer;

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

        // TODO: 여기에서 Storage(LogWriterWorker 등)로 넘기면 된다.
        // 예) logWriterWorker.enqueue(result);
        // 지금은 동작 확인을 위해 로그로만 출력.
        log.info("[FileCollector] 분석 결과 - ownerKey={}, eventType={}, path={}, exists={}, size={}, hash={}, entropy={}",
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
     * 실제 파일 시스템 상의 경로에 대해 메타데이터 + 해시 + 엔트로피 분석을 수행한다.
     *
     * @param ownerKey  세션/사용자 식별자
     * @param eventType 이벤트 타입 (CREATE / MODIFY / DELETE 등)
     * @param path      파일 절대 경로
     * @param eventTime Watcher가 이벤트를 감지한 시각
     * @return FileAnalysisResult
     */
    private FileAnalysisResult analyzePath(String ownerKey,
                                           String eventType,
                                           Path path,
                                           Instant eventTime) {

        boolean exists = Files.exists(path);
        long size = -1L;
        long lastModified = -1L;

        // DELETE 이벤트이거나, 파일이 존재하지 않으면
        // 메타데이터/해시/엔트로피를 계산하지 않고 기본값만 세팅.
        if (exists && Files.isRegularFile(path)) {
            try {
                BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
                size = attrs.size();
                lastModified = attrs.lastModifiedTime().toMillis();
            } catch (IOException e) {
                log.warn("[FileCollector] 파일 메타데이터 조회 중 예외 발생: {}", path, e);
            }
        }

        // 해시 / 엔트로피 계산 (파일이 존재하고 일반 파일일 때만)
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
                .collectedAt(Instant.now())  // Collector가 실제로 분석을 수행한 시각
                .exists(exists)
                .size(size)
                .lastModifiedTime(lastModified)
                .hash(hash)
                .entropy(entropy)
                .build();
    }
}
