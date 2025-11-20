package com.watchserviceagent.watchservice_agent.watcher;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;

/**
 * WatcherService에 대한 REST 엔드포인트.
 *
 * - /watcher/start  : 감시 시작
 * - /watcher/stop   : 감시 중지
 *
 * 실제 프로젝트에서는 ResponseEntity, 에러 코드, DTO 등을 더 세련되게 쓸 수 있지만
 * 졸업프로젝트 1차 버전에서는 문자열 응답만으로도 충분하다.
 */
@Slf4j
@RestController
@RequestMapping("/watcher")
@RequiredArgsConstructor
public class WatcherController {

    private final WatcherService watcherService;

    /**
     * 감시 시작 요청.
     *
     * @param folderPath 감시할 루트 경로 (폴더 또는 파일)
     * @return 시작 결과 메시지
     */
    @PostMapping("/start")
    public String startWatching(@RequestParam String folderPath) {
        try {
            watcherService.startWatching(folderPath);
            return "[Watcher] 감시를 시작했습니다: " + folderPath;
        } catch (Exception e) {
            log.error("[Watcher] 감시 시작 실패 - path={}", folderPath, e);
            return "[Watcher] 감시 시작 실패: " + e.getMessage();
        }
    }

    /**
     * 감시 중지 요청.
     *
     * @return 중지 결과 메시지
     */
    @PostMapping("/stop")
    public String stopWatching() {
        try {
            watcherService.stopWatching();
            return "[Watcher] 감시를 중지했습니다.";
        } catch (IOException e) {
            log.error("[Watcher] 감시 중지 실패", e);
            return "[Watcher] 감시 중지 실패: " + e.getMessage();
        }
    }
}
