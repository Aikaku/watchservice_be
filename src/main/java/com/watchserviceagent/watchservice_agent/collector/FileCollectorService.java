package com.watchserviceagent.watchservice_agent.collector;

import com.watchserviceagent.watchservice_agent.collector.business.EntropyAnalyzer;
import com.watchserviceagent.watchservice_agent.collector.dto.FileAnalysisResult;
import com.watchserviceagent.watchservice_agent.watcher.dto.WatcherEventRecord;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WatcherEventRecord 를 입력으로 받아
 * 파일의 "변경 전/후 상태"를 계산하는 Collector 서비스.
 *
 * - 이전 상태는 프로세스 내 메모리(Map)에 유지되는 스냅샷을 사용한다.
 * - sizeBefore, entropyBefore, extBefore 는 직전 이벤트 기준 값이다.
 * - sizeAfter, entropyAfter, extAfter 는 현재(이벤트 직후) 파일 기준 값이다.
 *
 * 이 서비스는:
 * 1) 나중에 Storage(Log) 모듈에 기록할 때도 사용 가능하고,
 * 2) EventWindowAggregator 가 9개 피처를 계산할 때도 재료로 사용된다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FileCollectorService {

    private final EntropyAnalyzer entropyAnalyzer;
    // TODO: HashCalculator 가 필요하면 주입하여 hash 계산도 추가 가능.
    // private final HashCalculator hashCalculator;

    /**
     * path → 마지막으로 알려진 파일 스냅샷.
     * (프로세스가 살아있는 동안만 유지되는 in-memory baseline)
     */
    private final Map<String, FileSnapshot> lastSnapshotMap = new ConcurrentHashMap<>();

    /**
     * Watcher 에서 발생한 파일 이벤트를 처리하여,
     * FileAnalysisResult (변경 전/후 정보) 를 계산한다.
     *
     * @param event WatcherEventRecord (ownerKey, eventType, path, eventTimeMs)
     * @return FileAnalysisResult (변경 전/후 상태 요약)
     */
    public FileAnalysisResult analyze(WatcherEventRecord event) {
        String ownerKey = event.getOwnerKey();
        String eventType = event.getEventType();
        String pathStr = event.getPath();
        Instant eventTime = Instant.ofEpochMilli(event.getEventTimeMs());

        if (pathStr == null) {
            log.warn("WatcherEventRecord.path is null. event={}", event);
            return buildResultWithNoFile(ownerKey, eventType, eventTime, null);
        }

        Path path = Paths.get(pathStr);

        // 1) 이전 스냅샷 조회
        FileSnapshot prev = lastSnapshotMap.get(pathStr);
        Long sizeBefore = (prev != null) ? prev.size : null;
        Double entropyBefore = (prev != null) ? prev.entropy : null;
        String extBefore = (prev != null) ? prev.ext : null;

        // 2) 현재 파일 상태 계산 (존재 여부, sizeAfter, entropyAfter, extAfter)
        boolean existsNow = Files.exists(path) && Files.isRegularFile(path);

        Long sizeAfter = null;
        Double entropyAfter = null;
        String extAfter = null;

        if (existsNow && !eventType.equalsIgnoreCase("DELETE")) {
            try {
                sizeAfter = Files.size(path);
            } catch (IOException e) {
                log.warn("Failed to get file size: {}", path, e);
            }

            try {
                // 4KB 정도만 샘플링해서 엔트로피 계산
                entropyAfter = entropyAnalyzer.computeSampleEntropy(path, 4096);
            } catch (IOException e) {
                log.warn("Failed to compute entropy: {}", path, e);
            }

            extAfter = extractExtension(pathStr);
        }

        // 3) FileAnalysisResult 생성
        FileAnalysisResult result = FileAnalysisResult.builder()
                .ownerKey(ownerKey)
                .eventType(eventType)
                .path(pathStr)
                .eventTime(eventTime)
                .sizeBefore(sizeBefore)
                .sizeAfter(sizeAfter)
                .entropyBefore(entropyBefore)
                .entropyAfter(entropyAfter)
                .extBefore(extBefore)
                .extAfter(extAfter)
                .existsAfter(existsNow)
                .build();

        log.debug("FileAnalysisResult: {}", result);

        // 4) 스냅샷 갱신
        updateSnapshot(pathStr, eventType, existsNow, sizeAfter, entropyAfter, extAfter);

        // TODO:
        // - 여기서 Storage(LogService)에 result를 전달하여 DB 에 기록하거나,
        // - EventWindowAggregator 에 전달하여 윈도우 단위 피처 집계에 사용하게 할 수 있다.
        //   ex) eventWindowAggregator.onFileAnalysisResult(result);

        return result;
    }

    /**
     * path 가 null 이거나, 파일 시스템에서 더 이상 접근할 수 없는 경우
     * 최소한의 정보만 담은 FileAnalysisResult 를 생성.
     */
    private FileAnalysisResult buildResultWithNoFile(String ownerKey,
                                                     String eventType,
                                                     Instant eventTime,
                                                     String pathStr) {
        return FileAnalysisResult.builder()
                .ownerKey(ownerKey)
                .eventType(eventType)
                .path(pathStr)
                .eventTime(eventTime)
                .sizeBefore(null)
                .sizeAfter(null)
                .entropyBefore(null)
                .entropyAfter(null)
                .extBefore(null)
                .extAfter(null)
                .existsAfter(false)
                .build();
    }

    /**
     * 파일 스냅샷 맵 갱신 로직.
     */
    private void updateSnapshot(String pathStr,
                                String eventType,
                                boolean existsNow,
                                Long sizeAfter,
                                Double entropyAfter,
                                String extAfter) {

        if (!existsNow || "DELETE".equalsIgnoreCase(eventType)) {
            // 삭제된 파일은 스냅샷에서 제거
            lastSnapshotMap.remove(pathStr);
            return;
        }

        // CREATE / MODIFY 등에서 최신 상태로 갱신
        FileSnapshot snapshot = new FileSnapshot(sizeAfter, entropyAfter, extAfter);
        lastSnapshotMap.put(pathStr, snapshot);
    }

    /**
     * 파일 경로 문자열에서 확장자(소문자, '.' 없이)를 추출.
     * 예: "/path/to/test.TXT" -> "txt"
     * 확장자가 없으면 null.
     */
    private String extractExtension(String pathStr) {
        if (pathStr == null) return null;
        int lastDot = pathStr.lastIndexOf('.');
        if (lastDot < 0 || lastDot == pathStr.length() - 1) {
            return null;
        }
        String ext = pathStr.substring(lastDot + 1).trim().toLowerCase(Locale.ROOT);
        return ext.isEmpty() ? null : ext;
    }

    /**
     * Collector 가 유지하는 "이전 파일 상태" 스냅샷.
     */
    private static class FileSnapshot {
        final Long size;
        final Double entropy;
        final String ext;

        FileSnapshot(Long size, Double entropy, String ext) {
            this.size = size;
            this.entropy = entropy;
            this.ext = ext;
        }
    }
}
