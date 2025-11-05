package com.watchserviceagent.watchservice_agent.service.watcher;

import com.watchserviceagent.watchservice_agent.dto.common.WatchEventRecord;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static java.nio.file.StandardWatchEventKinds.*;

/**
 * 클래스 이름 : WatcherService
 * 기능 : 사용자가 지정한 디렉토리를 감시하고 CREATE/MODIFY/DELETE 이벤트를
 *       WatchEventRecord DTO로 변환하여 JSON 형태로 로그에 출력한다.
 * 작성 날짜 : 2025/09/30
 * 작성자 : 이상혁
 */
@Service
@Slf4j
public class WatcherService {

    private ExecutorService executor;   // 감시 전용 스레드 풀
    private WatchService watchService;  // NIO WatchService
    private Path watchPath;             // 감시 경로

    /**
     @param folderPath 감시할 폴더 경로
     */

    /**
     * 실행 로직
    */
    public void startWatching(String folderPath) throws IOException {

        //=====메서드가 켜져 있으면서 실행중인 경우는 이미 실행중이다.=====//
        if (executor != null && !executor.isShutdown()) {
            log.warn("Watcher is already running for {}", watchPath);
            return;
        }

        //=====사용자가 지정한 경로를 감시 경로 객체로 지정=====//
        this.watchPath = Paths.get(folderPath);

        //=====사용자가 지정한 경로가 존재하지 않는 경우 예외 처리=====//
        if (!Files.exists(watchPath)) {
            throw new IllegalArgumentException("지정한 경로가 존재하지 않습니다: " + folderPath);
        }

        //=====운영체제 파일 감시기를 생성 및 객체에 대입=====//
        this.watchService = FileSystems.getDefault().newWatchService();

        //=====감시 전용 스레드를 싱글톤 스레드로 지정=====//
        this.executor = Executors.newSingleThreadExecutor();

        //=====루트 및 모든 하위 디렉토리 등록=====//
        registerAll(watchPath);

        //=====감시 루프 시작=====//
        executor.submit(() -> {
            log.info("Watcher started for {}", watchPath.toAbsolutePath());
            while (true) {
                WatchKey key;
                try {
                    key = watchService.take(); // 이벤트 대기 (Blocking)
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (ClosedWatchServiceException e) {
                    log.info("WatchService closed");
                    break;
                }

                for (WatchEvent<?> event : key.pollEvents()) {
                    // DTO 객체 생성
                    WatchEventRecord record = new WatchEventRecord(
                            event.kind().name().replace("ENTRY_", ""), // CREATE / MODIFY / DELETE
                            watchPath.resolve((Path) event.context()).toString(), // 파일 경로
                            Instant.now() // 이벤트 발생 시각
                    );

                    // JSON 직렬화된 문자열을 로그에 출력
                    log.info(record.toString());
                }

                if (!key.reset()) {
                    log.warn("WatchKey invalid, stopping watcher");
                    break;
                }
            }
        });
    }

    //=====비지니스 로직=====//
    /**
     * 지정된 경로와 그 하위의 모든 디렉토리를 재귀적으로 WatchService에 등록
     */
    private void registerAll(Path start) throws IOException {
        Files.walkFileTree(start, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                dir.register(watchService, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE);
                log.info("Registered directory: {}", dir.toAbsolutePath());
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * 감시 중지
     */
    public void stopWatching() throws IOException {
        if (watchService != null) {
            watchService.close();
        }
        if (executor != null) {
            executor.shutdownNow();
        }
        log.info("Watcher stopped for {}", watchPath != null ? watchPath.toAbsolutePath() : "unknown");
    }
}
