package com.watchserviceagent.watchservice_agent.settings.exceptionrule;

import com.watchserviceagent.watchservice_agent.settings.exceptionrule.domain.ExceptionRule;
import com.watchserviceagent.watchservice_agent.settings.exceptionrule.dto.ExceptionRuleRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * 예외 규칙 비즈니스 로직.
 */
@Service
@RequiredArgsConstructor
public class ExceptionRuleService {

    private final ExceptionRuleRepository repository;

    public List<ExceptionRule> getRules(String ownerKey) {
        return repository.findByOwnerKey(ownerKey);
    }

    public ExceptionRule createRule(String ownerKey, ExceptionRuleRequest request) {
        String type = (request.getType() != null) ? request.getType().trim().toUpperCase() : "";
        String pattern = (request.getPattern() != null) ? request.getPattern().trim() : "";
        String memo = (request.getMemo() != null) ? request.getMemo().trim() : "";

        if (type.isEmpty() || pattern.isEmpty()) {
            throw new IllegalArgumentException("type과 pattern은 필수입니다.");
        }

        if (!type.equals("PATH") && !type.equals("EXT")) {
            throw new IllegalArgumentException("type은 PATH 또는 EXT만 허용됩니다.");
        }

        ExceptionRule rule = ExceptionRule.builder()
                .ownerKey(ownerKey)
                .type(type)
                .pattern(pattern)
                .memo(memo)
                .createdAt(Instant.now())
                .build();

        return repository.insert(rule);
    }

    public void deleteRule(String ownerKey, Long id) {
        repository.deleteByIdAndOwnerKey(id, ownerKey);
    }
}
