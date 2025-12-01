package com.watchserviceagent.watchservice_agent.settings;

import com.watchserviceagent.watchservice_agent.settings.dto.WatchedFolderRequest;
import com.watchserviceagent.watchservice_agent.settings.dto.WatchedFolderResponse;
import com.watchserviceagent.watchservice_agent.settings.dto.ExceptionRuleRequest;
import com.watchserviceagent.watchservice_agent.settings.dto.ExceptionRuleResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 설정(감시 폴더, 예외 규칙) 관련 REST API.
 *
 * - 감시 폴더:
 *   GET    /settings/folders
 *   POST   /settings/folders
 *   DELETE /settings/folders/{id}
 *
 * - 예외 규칙:
 *   GET    /settings/exceptions
 *   POST   /settings/exceptions
 *   DELETE /settings/exceptions/{id}
 */
@RestController
@RequestMapping("/settings")
@RequiredArgsConstructor
@Slf4j
public class SettingsController {

    private final SettingsService settingsService;

    // ===== 감시 폴더 =====

    @GetMapping("/folders")
    public List<WatchedFolderResponse> getWatchedFolders() {
        List<WatchedFolderResponse> list = settingsService.getWatchedFolders();
        log.info("[SettingsController] GET /settings/folders -> {}건", list.size());
        return list;
    }

    @PostMapping("/folders")
    public WatchedFolderResponse addWatchedFolder(@RequestBody WatchedFolderRequest req) {
        WatchedFolderResponse resp = settingsService.addWatchedFolder(req);
        log.info("[SettingsController] POST /settings/folders -> {}", resp);
        return resp;
    }

    @DeleteMapping("/folders/{id}")
    public void deleteWatchedFolder(@PathVariable("id") Long id) {
        settingsService.deleteWatchedFolder(id);
        log.info("[SettingsController] DELETE /settings/folders/{}", id);
    }

    // ===== 예외 규칙 =====

    @GetMapping("/exceptions")
    public List<ExceptionRuleResponse> getExceptionRules() {
        List<ExceptionRuleResponse> list = settingsService.getExceptionRules();
        log.info("[SettingsController] GET /settings/exceptions -> {}건", list.size());
        return list;
    }

    @PostMapping("/exceptions")
    public ExceptionRuleResponse addExceptionRule(@RequestBody ExceptionRuleRequest req) {
        ExceptionRuleResponse resp = settingsService.addExceptionRule(req);
        log.info("[SettingsController] POST /settings/exceptions -> {}", resp);
        return resp;
    }

    @DeleteMapping("/exceptions/{id}")
    public void deleteExceptionRule(@PathVariable("id") Long id) {
        settingsService.deleteExceptionRule(id);
        log.info("[SettingsController] DELETE /settings/exceptions/{}", id);
    }
}
