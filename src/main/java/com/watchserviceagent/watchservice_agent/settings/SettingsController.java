package com.watchserviceagent.watchservice_agent.settings;

import com.watchserviceagent.watchservice_agent.settings.dto.ExceptionRuleRequest;
import com.watchserviceagent.watchservice_agent.settings.dto.ExceptionRuleResponse;
import com.watchserviceagent.watchservice_agent.settings.dto.WatchedFolderRequest;
import com.watchserviceagent.watchservice_agent.settings.dto.WatchedFolderResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import javax.swing.*;
import java.awt.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/settings")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = {"http://localhost:3000", "http://localhost:5173"})
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

    /**
     * ✅ 폴더 선택 다이얼로그
     * GET /settings/folders/pick -> { "path": "C:\\Users\\..." } or { "path": "" }
     */
    @GetMapping("/folders/pick")
    public Map<String, String> pickFolder() {
        log.info("[SettingsController] GET /settings/folders/pick");

        try {
            // Swing은 EDT에서 동작 권장
            final String[] chosen = new String[]{""};

            EventQueue.invokeAndWait(() -> {
                JFileChooser chooser = new JFileChooser();
                chooser.setDialogTitle("감시할 폴더를 선택하세요");
                chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
                chooser.setAcceptAllFileFilterUsed(false);

                int result = chooser.showOpenDialog(null);
                if (result == JFileChooser.APPROVE_OPTION && chooser.getSelectedFile() != null) {
                    chosen[0] = chooser.getSelectedFile().getAbsolutePath();
                }
            });

            log.info("[SettingsController] picked path={}", chosen[0]);
            return Map.of("path", chosen[0] == null ? "" : chosen[0]);

        } catch (Exception e) {
            log.error("[SettingsController] folder pick failed", e);
            // 프론트에서 alert로 보이게 에러 던져도 됨. 일단 빈값 반환.
            return Map.of("path", "");
        }
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
