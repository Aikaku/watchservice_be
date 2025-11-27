package com.watchserviceagent.watchservice_agent.settings.exceptionrule;

import com.watchserviceagent.watchservice_agent.settings.exceptionrule.domain.ExceptionRule;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

/**
 * 예외(화이트리스트) 규칙 SQLite 저장소.
 */
@Repository
@RequiredArgsConstructor
@Slf4j
public class ExceptionRuleRepository {

    private final JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void initTable() {
        String sql = """
            CREATE TABLE IF NOT EXISTS exception_rule (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              owner_key TEXT NOT NULL,
              type TEXT NOT NULL,
              pattern TEXT NOT NULL,
              memo TEXT,
              created_at TEXT NOT NULL
            )
            """;
        jdbcTemplate.execute(sql);
        log.info("[ExceptionRuleRepository] exception_rule 테이블 초기화 완료");
    }

    private RowMapper<ExceptionRule> rowMapper() {
        return new RowMapper<ExceptionRule>() {
            @Override
            public ExceptionRule mapRow(ResultSet rs, int rowNum) throws SQLException {
                return ExceptionRule.builder()
                        .id(rs.getLong("id"))
                        .ownerKey(rs.getString("owner_key"))
                        .type(rs.getString("type"))
                        .pattern(rs.getString("pattern"))
                        .memo(rs.getString("memo"))
                        .createdAt(Instant.parse(rs.getString("created_at")))
                        .build();
            }
        };
    }

    public List<ExceptionRule> findByOwnerKey(String ownerKey) {
        String sql = """
            SELECT id, owner_key, type, pattern, memo, created_at
            FROM exception_rule
            WHERE owner_key = ?
            ORDER BY id DESC
            """;
        return jdbcTemplate.query(sql, rowMapper(), ownerKey);
    }

    public ExceptionRule insert(ExceptionRule rule) {
        String sql = """
            INSERT INTO exception_rule (owner_key, type, pattern, memo, created_at)
            VALUES (?, ?, ?, ?, ?)
            """;

        jdbcTemplate.update(sql,
                rule.getOwnerKey(),
                rule.getType(),
                rule.getPattern(),
                rule.getMemo(),
                rule.getCreatedAt().toString()
        );

        // 방금 insert한 row를 다시 조회 (owner_key 기준 가장 최근 id)
        String selectSql = """
            SELECT id, owner_key, type, pattern, memo, created_at
            FROM exception_rule
            WHERE owner_key = ?
            ORDER BY id DESC
            LIMIT 1
            """;
        return jdbcTemplate.queryForObject(selectSql, rowMapper(), rule.getOwnerKey());
    }

    public void deleteByIdAndOwnerKey(Long id, String ownerKey) {
        String sql = "DELETE FROM exception_rule WHERE id = ? AND owner_key = ?";
        jdbcTemplate.update(sql, id, ownerKey);
    }
}
