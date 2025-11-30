package com.watchserviceagent.watchservice_agent.watcher;

import com.watchserviceagent.watchservice_agent.analytics.EventWindowAggregator;
import com.watchserviceagent.watchservice_agent.collector.FileCollectorService;
import com.watchserviceagent.watchservice_agent.collector.dto.FileAnalysisResult;
import com.watchserviceagent.watchservice_agent.storage.LogService;
import com.watchserviceagent.watchservice_agent.common.util.SessionIdManager;
import com.watchserviceagent.watchservice_agent.watcher.domain.WatcherEvent;
import com.watchserviceagent.watchservice_agent.watcher.dto.WatcherEventRecord;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchEvent.Kind;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Java NIO WatchService 를 사용하여 디렉토리/하위 디렉토리를 감시하는 서비스.
 *
 * 전체 흐름:
 *  1) WatchService 로 OS 파일 변경 이벤트(생성/수정/삭제)를 감지
 *  2) WatcherEventRecord 로 변환 (ownerKey, eventType, path, eventTimeMs)
 *  3) FileCollectorService.analyze() 로 변경 전/후 상태 계산
 *  4) LogService.saveAsync() 로 비동기 로그 저장 큐에 적재
 *  5) EventWindowAggregator.onFileAnalysisResult() 로 윈도우 단위 피처 집계
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WatcherService {

    private final SessionIdManager sessionIdManager;
    private final FileCollectorService fileCollectorService;
    private final EventWindowAggregator eventWindowAggregator;
    private final LogService logService; // 비동기 로그 저장 서비스

    private WatchService watchService;
    private Thread watcherThread;
    private volatile boolean running = false;

    /**
     * key → 디렉토리 경로 매핑 (하위 디렉토리 감시용)
     */
    private final Map<WatchKey, Path> keyDirMap = new ConcurrentHashMap<>();

    /**
     * 감시 시작.
     *
     * @param folderPath 감시할 루트 폴더 (문자열, 절대경로 권장)
     */
    public synchronized void startWatching(String folderPath) throws IOException {
        if (running) {
            log.info("Watcher already running. ignore startWatching request. path={}", folderPath);
            return;
        }

        Path root = Paths.get(folderPath);
        if (!Files.exists(root) || !Files.isDirectory(root)) {
            throw new IllegalArgumentException("감시할 경로가 존재하지 않거나 디렉토리가 아닙니다: " + folderPath);
        }

        this.watchService = FileSystems.getDefault().newWatchService();
        this.keyDirMap.clear();

        // 루트 + 하위 디렉토리 재귀 등록
        registerAll(root);

        running = true;

        // 별도 스레드에서 watchLoop 실행
        watcherThread = new Thread(this::watchLoop, "WatcherService-Thread");
        watcherThread.start();

        log.info("Started watching folder: {}", folderPath);
    }

    /**
     * 모든 하위 디렉토리를 재귀적으로 순회하며 WatchService 에 등록.
     */
    private void registerAll(Path start) throws IOException {
        Files.walkFileTree(start, new java.util.HashSet<>(), Integer.MAX_VALUE, new java.nio.file.SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                register(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * 개별 디렉토리를 WatchService 에 등록.
     */
    private void register(Path dir) throws IOException {
        WatchKey key = dir.register(
                watchService,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_MODIFY,
                StandardWatchEventKinds.ENTRY_DELETE
        );
        keyDirMap.put(key, dir);
        log.info("Registered directory for watching: {}", dir);
    }

    /**
     * 감시 중지.
     */
    public synchronized void stopWatching() {
        if (!running) {
            log.info("Watcher is not running. ignore stopWatching request.");
            return;
        }

        running = false;

        if (watchService != null) {
            try {
                watchService.close();
            } catch (IOException e) {
                log.warn("Failed to close WatchService", e);
            }
        }

        if (watcherThread != null) {
            watcherThread.interrupt();
            try {
                watcherThread.join(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Interrupted while waiting for watcherThread to join", e);
            }
        }

        keyDirMap.clear();
        log.info("Stopped watching.");
    }

    /**
     * WatchService 이벤트 루프.
     *
     * - WatchKey 를 꺼내서, 등록된 디렉토리 기준으로 이벤트를 해석하고,
     * - WatcherEventRecord 로 변환한 뒤 Collector/Analytics/Storage 로 전달.
     */
    private void watchLoop() {
        String ownerKey = sessionIdManager.getSessionId();
        log.info("Watcher loop started. ownerKey={}", ownerKey);

        try {
            while (running) {
                WatchKey key;
                try {
                    key = watchService.take(); // 블로킹
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.info("Watcher loop interrupted");
                    break;
                } catch (ClosedWatchServiceException e) {
                    log.info("WatchService has been closed. stop watcher loop.");
                    break;
                }

                Path dir = keyDirMap.get(key);
                if (dir == null) {
                    log.warn("WatchKey not recognized. skip.");
                    boolean valid = key.reset();
                    if (!valid) {
                        keyDirMap.remove(key);
                    }
                    continue;
                }

                for (WatchEvent<?> event : key.pollEvents()) {
                    Kind<?> kind = event.kind();

                    if (kind == StandardWatchEventKinds.OVERFLOW) {
                        log.warn("WatchService overflow event occurred. some events may have been lost.");
                        continue;
                    }

                    @SuppressWarnings("unchecked")
                    WatchEvent<Path> ev = (WatchEvent<Path>) event;
                    Path name = ev.context();
                    Path child = dir.resolve(name);

                    String eventType = mapKindToEventType(kind);
                    long eventTimeMs = System.currentTimeMillis();

                    WatcherEventRecord record = WatcherEventRecord.builder()
                            .ownerKey(ownerKey)
                            .eventType(eventType)
                            .path(child.toAbsolutePath().toString())
                            .eventTimeMs(eventTimeMs)
                            .build();

                    log.debug("Watcher event: {}", record);

                    // Collector 에서 변경 전/후 상태 분석
                    FileAnalysisResult analysisResult = fileCollectorService.analyze(record);

                    // 비동기 로그 저장 및 윈도우 단위 피처 집계기로 전달
                    logService.saveAsync(analysisResult);
                    eventWindowAggregator.onFileAnalysisResult(analysisResult);

                    // 디렉토리가 새로 생성되면 하위 디렉토리도 추가 등록
                    if (kind == StandardWatchEventKinds.ENTRY_CREATE) {
                        if (Files.isDirectory(child)) {
                            try {
                                registerAll(child);
                            } catch (IOException e) {
                                log.warn("Failed to register sub directory: {}", child, e);
                            }
                        }
                    }
                }

                boolean valid = key.reset();
                if (!valid) {
                    keyDirMap.remove(key);
                    if (keyDirMap.isEmpty()) {
                        log.info("No directories are being watched anymore. stopping watchLoop.");
                        break;
                    }
                }
            }
        } catch (ClosedWatchServiceException e) {
            log.info("WatchService has been closed. watcher loop will exit.");
        } catch (Exception e) {
            log.error("Unexpected error in watcher loop", e);
        } finally {
            running = false;
            log.info("Watcher loop finished.");
        }
    }

    /**
     * Java NIO 이벤트 Kind → 우리 쪽 이벤트 타입 문자열 매핑.
     */
    private String mapKindToEventType(Kind<?> kind) {
        if (kind == StandardWatchEventKinds.ENTRY_CREATE) {
            return "CREATE";
        } else if (kind == StandardWatchEventKinds.ENTRY_MODIFY) {
            return "MODIFY";
        } else if (kind == StandardWatchEventKinds.ENTRY_DELETE) {
            return "DELETE";
        } else {
            return "UNKNOWN";
        }
    }

    public boolean isRunning() {
        return running;
    }
}
