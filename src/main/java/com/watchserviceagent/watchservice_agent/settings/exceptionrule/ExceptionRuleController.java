package com.watchserviceagent.watchservice_agent.settings.exceptionrule;

import com.watchserviceagent.watchservice_agent.common.util.SessionIdManager;
import com.watchserviceagent.watchservice_agent.settings.exceptionrule.domain.ExceptionRule;
import com.watchserviceagent.watchservice_agent.settings.exceptionrule.dto.ExceptionRuleRequest;
import com.watchserviceagent.watchservice_agent.settings.exceptionrule.dto.ExceptionRuleResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 예외(화이트리스트) 규칙 REST 컨트롤러.
 *
 * base path: /settings/exceptions
 */
@RestController
@RequestMapping("/settings/exceptions")
@RequiredArgsConstructor
@Slf4j
public class ExceptionRuleController {

    private final ExceptionRuleService exceptionRuleService;
    private final SessionIdManager sessionIdManager;

    @GetMapping
    public List<ExceptionRuleResponse> list() {
        String ownerKey = sessionIdManager.getSessionId();
        log.info("[ExceptionRuleController] 예외 규칙 목록 조회 - ownerKey={}", ownerKey);

        List<ExceptionRule> rules = exceptionRuleService.getRules(ownerKey);
        return rules.stream()
                .map(ExceptionRuleResponse::from)
                .collect(Collectors.toList());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ExceptionRuleResponse create(@RequestBody ExceptionRuleRequest request) {
        String ownerKey = sessionIdManager.getSessionId();
        log.info("[ExceptionRuleController] 예외 규칙 생성 요청 - ownerKey={}, body={}", ownerKey, request);

        ExceptionRule rule = exceptionRuleService.createRule(ownerKey, request);
        return ExceptionRuleResponse.from(rule);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable("id") Long id) {
        String ownerKey = sessionIdManager.getSessionId();
        log.info("[ExceptionRuleController] 예외 규칙 삭제 요청 - ownerKey={}, id={}", ownerKey, id);

        exceptionRuleService.deleteRule(ownerKey, id);
    }
}
