package com.watchserviceagent.watchservice_agent.watcher;

import com.watchserviceagent.watchservice_agent.analytics.EventWindowAggregator;
import com.watchserviceagent.watchservice_agent.collector.FileCollectorService;
import com.watchserviceagent.watchservice_agent.collector.dto.FileAnalysisResult;
import com.watchserviceagent.watchservice_agent.common.util.SessionIdManager;
import com.watchserviceagent.watchservice_agent.watcher.domain.WatcherEvent;
import com.watchserviceagent.watchservice_agent.watcher.dto.WatcherEventRecord;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static java.nio.file.StandardWatchEventKinds.*;

/**
 * NIO WatchService 를 이용하여 디렉토리(및 하위 디렉토리)를 실시간 감시하는 서비스.
 *
 * - /watcher/start?folderPath=... 에서 이 서비스를 통해 감시 시작
 * - /watcher/stop 에서 감시 중단
 *
 * 이벤트 발생 시:
 *  1) WatcherEvent / WatcherEventRecord 생성
 *  2) FileCollectorService.analyze() 로 변경 전/후 상태 계산
 *  3) EventWindowAggregator.onFileAnalysisResult() 로 윈도우 단위 피처 집계
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WatcherService {

    private final SessionIdManager sessionIdManager;
    private final FileCollectorService fileCollectorService;
    private final EventWindowAggregator eventWindowAggregator;

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

        watcherThread = new Thread(this::watchLoop, "watchservice-agent-watcher-thread");
        watcherThread.setDaemon(true);
        watcherThread.start();

        log.info("Start watching path = {}", root.toAbsolutePath());
    }

    /**
     * 감시 중지.
     */
    public synchronized void stopWatching() {
        running = false;
        if (watcherThread != null) {
            watcherThread.interrupt();
            watcherThread = null;
        }
        if (watchService != null) {
            try {
                watchService.close();
            } catch (IOException e) {
                log.warn("Failed to close watchService", e);
            } finally {
                watchService = null;
            }
        }
        keyDirMap.clear();

        // 남아 있는 윈도우가 있으면 flush
        eventWindowAggregator.flushIfNeeded();

        log.info("Stop watching");
    }

    /**
     * WatchService 메인 루프.
     */
    private void watchLoop() {
        while (running) {
            WatchKey key;
            try {
                key = watchService.take(); // blocking
            } catch (InterruptedException e) {
                if (!running) {
                    log.info("Watcher thread interrupted because of stopWatching");
                    return;
                }
                log.warn("Watcher thread interrupted unexpectedly", e);
                continue;
            } catch (ClosedWatchServiceException e) {
                log.info("WatchService closed, exiting watchLoop");
                return;
            }

            Path dir = keyDirMap.get(key);
            if (dir == null) {
                log.warn("WatchKey not recognized (directory mapping missing). key={}", key);
                boolean valid = key.reset();
                if (!valid) {
                    keyDirMap.remove(key);
                }
                continue;
            }

            for (WatchEvent<?> event : key.pollEvents()) {
                WatchEvent.Kind<?> kind = event.kind();

                // OVERFLOW 무시
                if (kind == StandardWatchEventKinds.OVERFLOW) {
                    log.warn("WatchService OVERFLOW occurred");
                    continue;
                }

                @SuppressWarnings("unchecked")
                WatchEvent<Path> ev = (WatchEvent<Path>) event;
                Path name = ev.context();
                Path child = dir.resolve(name);

                String ownerKey = sessionIdManager.getSessionId();
                String eventType = mapKindToEventType(kind);
                Instant now = Instant.now();

                WatcherEvent watcherEvent = WatcherEvent.builder()
                        .ownerKey(ownerKey)
                        .eventType(eventType)
                        .fullPath(child.toAbsolutePath())
                        .eventTime(now)
                        .build();

                WatcherEventRecord record = WatcherEventRecord.from(watcherEvent);

                log.debug("WatcherEvent: {}", watcherEvent);

                // 하위 디렉토리가 새로 생성된 경우, 재귀 등록
                if (kind == ENTRY_CREATE) {
                    if (Files.isDirectory(child)) {
                        try {
                            registerAll(child);
                        } catch (IOException e) {
                            log.warn("Failed to register sub directory: {}", child, e);
                        }
                    }
                }

                // Collector 에서 변경 전/후 상태 분석
                FileAnalysisResult analysisResult = fileCollectorService.analyze(record);

                // 윈도우 단위 피처 집계기에게 전달
                eventWindowAggregator.onFileAnalysisResult(analysisResult);
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
    }

    /**
     * 단일 디렉토리 등록.
     */
    private void registerDir(Path dir) throws IOException {
        WatchKey key = dir.register(
                watchService,
                ENTRY_CREATE,
                ENTRY_MODIFY,
                ENTRY_DELETE
        );
        keyDirMap.put(key, dir);
        log.info("Registered directory for watching: {}", dir.toAbsolutePath());
    }

    /**
     * 루트 디렉토리와 모든 하위 디렉토리를 재귀적으로 WatchService 에 등록.
     */
    private void registerAll(final Path start) throws IOException {
        Files.walkFileTree(start, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                registerDir(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * NIO WatchEvent.Kind → "CREATE" / "MODIFY" / "DELETE" 문자열로 매핑.
     */
    private String mapKindToEventType(WatchEvent.Kind<?> kind) {
        if (kind == ENTRY_CREATE) {
            return "CREATE";
        } else if (kind == ENTRY_MODIFY) {
            return "MODIFY";
        } else if (kind == ENTRY_DELETE) {
            return "DELETE";
        } else {
            return "UNKNOWN";
        }
    }

    public boolean isRunning() {
        return running;
    }
}
