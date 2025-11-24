package com.watchserviceagent.watchservice_agent.watcher;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;

@Slf4j
@RestController
@RequestMapping("/watcher")
@RequiredArgsConstructor
public class WatcherController {

    private final WatcherService watcherService;

    // ===================== 감시 시작 =====================

    /**
     * 감시 시작 (POST 요청용)
     * curl -X POST "http://localhost:8080/watcher/start?folderPath=/path/..."
     */
    @PostMapping("/start")
    public String startWatchingPost(@RequestParam("folderPath") String folderPath) {
        return startInternal(folderPath);
    }

    /**
     * 감시 시작 (GET 요청용)
     * 브라우저 주소창에서 바로 호출 가능:
     * http://localhost:8080/watcher/start?folderPath=/Users/...
     */
    @GetMapping("/start")
    public String startWatchingGet(@RequestParam("folderPath") String folderPath) {
        return startInternal(folderPath);
    }

    /**
     * 실제 감시 시작 로직 (GET/POST 공통)
     */
    private String startInternal(String folderPath) {
        log.info("[WatcherController] 감시 시작 요청 - folderPath={}", folderPath);
        try {
            watcherService.startWatching(folderPath);
            return "[Watcher] 감시를 시작했습니다: " + folderPath;
        } catch (Exception e) {
            log.error("[WatcherController] 감시 시작 실패", e);
            return "[Watcher] 감시 시작 실패: " + e.getMessage();
        }
    }

    // ===================== 감시 중지 =====================

    /**
     * 감시 중지 (POST)
     */
    @PostMapping("/stop")
    public String stopWatchingPost() {
        return stopInternal();
    }

    /**
     * 감시 중지 (GET)
     * 브라우저에서 http://localhost:8080/watcher/stop 로 호출 가능
     */
    @GetMapping("/stop")
    public String stopWatchingGet() {
        return stopInternal();
    }

    private String stopInternal() {
        log.info("[WatcherController] 감시 중지 요청");
        try {
            watcherService.stopWatching();
            return "[Watcher] 감시를 중지했습니다.";
        } catch (IOException e) {
            log.error("[WatcherController] 감시 중지 실패", e);
            return "[Watcher] 감시 중지 실패: " + e.getMessage();
        }
    }
}

