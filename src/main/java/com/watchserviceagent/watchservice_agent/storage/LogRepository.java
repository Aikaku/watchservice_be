package com.watchserviceagent.watchservice_agent.storage;

import com.watchserviceagent.watchservice_agent.storage.domain.Log;
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
 * SQLite log.db 의 log 테이블을 다루는 리포지토리.
 */
@Repository
@RequiredArgsConstructor
@Slf4j
public class LogRepository {

    private final JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void init() {
        String sql = """
                CREATE TABLE IF NOT EXISTS log (
                    id                 INTEGER PRIMARY KEY AUTOINCREMENT,
                    owner_key          TEXT NOT NULL,
                    event_type         TEXT NOT NULL,
                    path               TEXT NOT NULL,
                    exists_flag        INTEGER NOT NULL,
                    size               INTEGER NOT NULL,
                    last_modified_time INTEGER NOT NULL,
                    hash               TEXT,
                    entropy            REAL,
                    ai_label           TEXT,
                    ai_score           REAL,
                    ai_detail          TEXT,
                    collected_at       INTEGER NOT NULL
                );
                """;
        jdbcTemplate.execute(sql);
        log.info("[LogRepository] log 테이블 초기화 완료");
    }

    public void insertLog(Log logEntity) {
        String sql = """
                INSERT INTO log (
                    owner_key,
                    event_type,
                    path,
                    exists_flag,
                    size,
                    last_modified_time,
                    hash,
                    entropy,
                    ai_label,
                    ai_score,
                    ai_detail,
                    collected_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;

        jdbcTemplate.update(
                sql,
                logEntity.getOwnerKey(),
                logEntity.getEventType(),
                logEntity.getPath(),
                logEntity.isExists() ? 1 : 0,
                logEntity.getSize(),
                logEntity.getLastModifiedTime(),
                logEntity.getHash(),
                logEntity.getEntropy(),
                logEntity.getAiLabel(),
                logEntity.getAiScore(),
                logEntity.getAiDetail(),
                logEntity.getCollectedAt().toEpochMilli()
        );
    }

    /**
     * ownerKey 기준으로 최근 로그를 limit 개 조회 (최신순).
     */
    public List<Log> findRecentLogsByOwner(String ownerKey, int limit) {
        String sql = """
                SELECT
                    id,
                    owner_key,
                    event_type,
                    path,
                    exists_flag,
                    size,
                    last_modified_time,
                    hash,
                    entropy,
                    ai_label,
                    ai_score,
                    ai_detail,
                    collected_at
                FROM log
                WHERE owner_key = ?
                ORDER BY collected_at DESC, id DESC
                LIMIT ?
                """;

        return jdbcTemplate.query(sql, new Object[]{ownerKey, limit}, logRowMapper());
    }

    private RowMapper<Log> logRowMapper() {
        return new RowMapper<>() {
            @Override
            public Log mapRow(ResultSet rs, int rowNum) throws SQLException {
                return Log.builder()
                        .id(rs.getLong("id"))
                        .ownerKey(rs.getString("owner_key"))
                        .eventType(rs.getString("event_type"))
                        .path(rs.getString("path"))
                        .exists(rs.getInt("exists_flag") != 0)
                        .size(rs.getLong("size"))
                        .lastModifiedTime(rs.getLong("last_modified_time"))
                        .hash(rs.getString("hash"))
                        .entropy(rs.getObject("entropy") != null ? rs.getDouble("entropy") : null)
                        .aiLabel(rs.getString("ai_label"))
                        .aiScore(rs.getObject("ai_score") != null ? rs.getDouble("ai_score") : null)
                        .aiDetail(rs.getString("ai_detail"))
                        .collectedAt(Instant.ofEpochMilli(rs.getLong("collected_at")))
                        .build();
            }
        };
    }
}
