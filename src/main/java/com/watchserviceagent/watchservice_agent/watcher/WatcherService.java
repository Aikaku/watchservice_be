package com.watchserviceagent.watchservice_agent.watcher;

import com.watchserviceagent.watchservice_agent.collector.FileCollectorService;
import com.watchserviceagent.watchservice_agent.common.util.SessionIdManager;
import com.watchserviceagent.watchservice_agent.watcher.dto.WatcherEventRecord;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Java NIO WatchService 기반으로
 * 특정 폴더(및 하위 폴더)의 파일/디렉터리 변경 이벤트를 감지하는 서비스.
 *
 * 주요 역할:
 *  - startWatching(path)
 *      → 지정한 경로를 감시 대상에 등록하고
 *      → 별도 스레드에서 WatchService 이벤트 루프를 실행
 *  - stopWatching()
 *      → 감시 스레드 및 WatchService 정리
 *  - 내부적으로
 *      → ENTRY_CREATE / ENTRY_MODIFY / ENTRY_DELETE 이벤트를 감지하고
 *      → WatcherEventRecord로 만들어 Collector/Storage/AI로 넘길 수 있는 형태로 구성
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WatcherService {

    /**
     * 파일 해시/엔트로피 분석을 담당하는 Collector 서비스.
     * - 현재 WatcherService에서는 직접 사용하지 않고,
     *   나중에 이벤트 처리 시점에 연동할 Hook 용도로 주입해둔다.
     */
    private final FileCollectorService fileCollectorService;

    /**
     * 사용자(에이전트)별 고유 UUID(ownerKey)를 관리하는 유틸.
     * - static 메서드로 부르는 것이 아니라
     *   스프링 빈으로 주입받은 인스턴스에서 getSessionId()를 호출해야 한다.
     */
    private final SessionIdManager sessionIdManager;

    /** OS 파일 시스템 이벤트를 수신하는 Java NIO WatchService 인스턴스 */
    private WatchService watchService;

    /** WatchService 이벤트를 소비하는 전용 스레드 풀 (현재 1개 스레드만 사용) */
    private ExecutorService executor;

    /** 현재 감시 중인 루트 경로 */
    private Path watchRootPath;

    /**
     * 감시를 시작한다.
     *
     * @param folderPath 감시할 루트 경로 (폴더 또는 파일)
     * @throws IOException 경로가 없거나 등록 실패 시 발생
     */
    public synchronized void startWatching(String folderPath) throws IOException {
        // 이미 감시 중이라면 기존 것을 먼저 정리
        if (watchService != null) {
            log.info("[Watcher] 기존 감시가 실행 중이므로 먼저 중지합니다.");
            stopWatching();
        }

        // 1) 입력 경로를 절대 경로 + normalize 처리
        Path targetPath = Paths.get(folderPath).toAbsolutePath().normalize();

        // 2) 존재 여부 체크
        if (!Files.exists(targetPath)) {
            throw new NoSuchFileException("감시 대상 경로가 존재하지 않습니다: " + targetPath);
        }

        // 3) WatchService 생성 및 필드에 저장
        this.watchService = FileSystems.getDefault().newWatchService();
        // 감시 루프를 처리할 스레드 풀(단일 스레드)
        this.executor = Executors.newSingleThreadExecutor();
        this.watchRootPath = targetPath;

        // 4) 디렉터리인지, 파일인지에 따라 등록 방식 분기
        if (Files.isDirectory(targetPath)) {
            // 폴더인 경우: 해당 폴더 + 모든 하위 폴더를 재귀적으로 등록
            registerDirectoryRecursively(targetPath);
        } else {
            // 파일인 경우: 파일 자체는 WatchService에 등록할 수 없으므로
            // → 상위 디렉터리를 감시 대상으로 등록하고,
            // → 나중에 이벤트 발생 시 fullPath로 파일만 필터링해서 사용한다.
            Path parent = targetPath.getParent();
            if (parent == null) {
                throw new IllegalArgumentException("상위 디렉터리가 없는 파일은 감시할 수 없습니다: " + targetPath);
            }
            registerDirectoryRecursively(parent);
        }

        log.info("[Watcher] 감시 시작: {}", targetPath);

        // 5) WatchService 이벤트를 소비하는 감시 루프 시작 (별도 스레드)
        startWatchLoop();
    }

    /**
     * 지정된 디렉터리와 그 하위 디렉터리를 재귀적으로 순회하면서
     * 모든 디렉터리를 WatchService에 등록한다.
     *
     * @param startDir 감시를 시작할 루트 디렉터리
     */
    private void registerDirectoryRecursively(Path startDir) throws IOException {
        Files.walkFileTree(startDir, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                // 디렉터리를 방문할 때마다 WatchService에 등록
                registerSingleDirectory(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * 단일 디렉터리를 WatchService에 등록한다.
     * - ENTRY_CREATE / ENTRY_MODIFY / ENTRY_DELETE 이벤트를 모두 감지하도록 설정.
     *
     * @param dir 등록할 디렉터리 경로
     */
    private void registerSingleDirectory(Path dir) throws IOException {
        dir.register(
                watchService,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_MODIFY,
                StandardWatchEventKinds.ENTRY_DELETE
        );
        log.debug("[Watcher] 디렉터리 등록: {}", dir.toAbsolutePath());
    }

    /**
     * WatchService 이벤트를 처리하는 감시 루프를 시작한다.
     *
     * - executor(단일 스레드)에서 무한 루프를 돌며
     *   watchService.take()로 이벤트 발생을 기다린다.
     * - 키가 반환되면 해당 디렉터리(watchDir)에서 발생한 이벤트 목록을 순회하면서 처리.
     * - stopWatching() 또는 WatchService.close()가 호출되면 루프 종료.
     */
    private void startWatchLoop() {
        executor.submit(() -> {
            log.info("[Watcher] 감시 루프 시작");

            try {
                while (!Thread.currentThread().isInterrupted()) {
                    WatchKey key;
                    try {
                        // 이벤트가 발생할 때까지 블로킹 (InterruptedException, ClosedWatchServiceException 가능)
                        key = watchService.take();
                    } catch (ClosedWatchServiceException cwse) {
                        // WatchService가 close() 되면 여기로 들어온다.
                        log.info("[Watcher] WatchService가 종료되었습니다.");
                        break;
                    }

                    // 이 키가 감시 중인 디렉터리 경로
                    Path watchDir = (Path) key.watchable();

                    // 해당 디렉터리에서 발생한 모든 이벤트를 하나씩 처리
                    for (WatchEvent<?> event : key.pollEvents()) {
                        handleRawEvent(watchDir, event);
                    }

                    // key.reset()이 false이면 더 이상 유효하지 않은 디렉터리 (삭제 등)
                    boolean valid = key.reset();
                    if (!valid) {
                        log.warn("[Watcher] WatchKey가 더 이상 유효하지 않습니다: {}", watchDir);
                    }
                }
            } catch (InterruptedException ie) {
                // 스레드 인터럽트 시 정상 종료 루트
                Thread.currentThread().interrupt();
                log.info("[Watcher] 감시 루프 스레드가 인터럽트되어 종료됩니다.");
            } catch (Exception e) {
                // 그 외 예기치 못한 예외
                log.error("[Watcher] 감시 루프 중 예외 발생", e);
            } finally {
                // 루프 종료 시 WatchService 자원 해제
                try {
                    if (watchService != null) {
                        watchService.close();
                    }
                } catch (IOException e) {
                    log.warn("[Watcher] WatchService 종료 중 예외 발생", e);
                }
                log.info("[Watcher] 감시 루프 종료");
            }
        });
    }

    /**
     * WatchService에서 꺼낸 단일 이벤트를 처리하는 내부 메서드.
     *
     * @param watchDir 이 이벤트와 연결된 디렉터리 (key.watchable())
     * @param event    WatchEvent<?> (ENTRY_CREATE / ENTRY_MODIFY / ENTRY_DELETE / OVERFLOW 등)
     */
    @SuppressWarnings("unchecked")
    private void handleRawEvent(Path watchDir, WatchEvent<?> event) {
        WatchEvent.Kind<?> kind = event.kind();

        // OVERFLOW는 이벤트 큐가 넘쳐서 일부 이벤트가 유실되었음을 의미.
        // 여기서는 경고 로그만 남기고 무시.
        if (kind == StandardWatchEventKinds.OVERFLOW) {
            log.warn("[Watcher] OVERFLOW 이벤트 발생 - 일부 파일 변경 이벤트가 유실되었을 수 있습니다.");
            return;
        }

        // context()에는 watchDir을 기준으로 한 상대 경로(Path)가 들어있다.
        WatchEvent<Path> pathEvent = (WatchEvent<Path>) event;
        Path relativePath = pathEvent.context();
        // 감시 디렉터리(watchDir) 기준 상대경로 → 절대 경로로 변환
        Path fullPath = watchDir.resolve(relativePath).toAbsolutePath().normalize();

        // 이벤트 종류를 문자열로 매핑
        String eventType;
        if (kind == StandardWatchEventKinds.ENTRY_CREATE) {
            eventType = "CREATE";
        } else if (kind == StandardWatchEventKinds.ENTRY_MODIFY) {
            eventType = "MODIFY";
        } else if (kind == StandardWatchEventKinds.ENTRY_DELETE) {
            eventType = "DELETE";
        } else {
            // 이론상 도달하지 않지만, 향후 확장을 고려해 kind.name() 사용
            eventType = kind.name();
        }

        // 이벤트 감지 시각
        Instant now = Instant.now();
        // ✅ 세션 ID는 SessionIdManager 인스턴스에서 가져온다 (static 호출 X)
        String ownerKey = sessionIdManager.getSessionId();

        // Collector/Storage/AI로 넘길 수 있는 DTO 형태로 묶는다.
        WatcherEventRecord record = new WatcherEventRecord(
                ownerKey,
                eventType,
                fullPath.toString(),
                now
        );

        log.info("[Watcher] 이벤트 감지 - type={}, path={}", eventType, fullPath);

        // 만약 새로 생성된 것이 "폴더"라면, 그 폴더도 감시 대상에 추가해야 한다.
        if (kind == StandardWatchEventKinds.ENTRY_CREATE) {
            try {
                if (Files.isDirectory(fullPath)) {
                    registerDirectoryRecursively(fullPath);
                }
            } catch (IOException e) {
                log.warn("[Watcher] 신규 디렉터리 재귀 등록 실패: {}", fullPath, e);
            }
        }

        // ✅ 이 지점에서 Collector/Storage/AI 연동을 수행할 수 있다.
        try {
            // 예시) Collector에 전달해서 파일 해시/엔트로피 분석 및 이후 파이프라인으로 넘김
            // fileCollectorService.handleWatchEvent(record);
            //
            // 아직 FileCollectorService의 시그니처가 확정되지 않았으면
            // 위 코드를 주석으로 두고, 나중에 메서드 이름/파라미터에 맞게 연결하면 된다.

        } catch (Exception e) {
            log.error("[Watcher] Collector 호출 중 오류 발생", e);
        }
    }

    /**
     * 감시를 중지하고, WatchService 및 스레드 풀 자원을 해제한다.
     *
     * @throws IOException WatchService 종료 중 예외 발생 시
     */
    public synchronized void stopWatching() throws IOException {
        // 1) WatchService 닫기
        if (watchService != null) {
            watchService.close();
            watchService = null;
        }
        // 2) 감시 루프 스레드 풀 중지
        if (executor != null) {
            executor.shutdownNow();  // 인터럽트 후 즉시 종료 시도
            executor = null;
        }
        log.info("[Watcher] 감시 중지 완료: {}",
                watchRootPath != null ? watchRootPath.toAbsolutePath() : "unknown");
    }
}
