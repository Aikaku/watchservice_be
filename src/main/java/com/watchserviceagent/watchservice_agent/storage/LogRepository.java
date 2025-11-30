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
 * SQLite log 테이블에 접근하는 Repository.
 *
 * - 애플리케이션 시작 시 테이블이 없으면 자동으로 생성한다.
 * - insertLog(...) : 로그 한 건 INSERT
 * - findRecentLogsByOwner(...) : ownerKey 기준 최근 로그 조회
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class LogRepository {

    // Spring 이 주입하는 JdbcTemplate
    private final JdbcTemplate jdbcTemplate;

    /**
     * 애플리케이션 시작 시 log 테이블이 없으면 생성.
     *
     * 컬럼 설명:
     *  - id                INTEGER PRIMARY KEY AUTOINCREMENT
     *  - owner_key         TEXT
     *  - event_type        TEXT
     *  - path              TEXT
     *  - exists_flag       INTEGER (0 or 1)
     *  - size              INTEGER
     *  - last_modified     INTEGER (epoch millis)
     *  - hash              TEXT
     *  - entropy           REAL
     *  - ai_label          TEXT
     *  - ai_score          REAL
     *  - ai_detail         TEXT
     *  - collected_at      INTEGER (epoch millis)
     */
    @PostConstruct
    public void init() {
        String sql = """
            CREATE TABLE IF NOT EXISTS log (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              owner_key TEXT,
              event_type TEXT,
              path TEXT,
              exists_flag INTEGER,
              size INTEGER,
              last_modified INTEGER,
              hash TEXT,
              entropy REAL,
              ai_label TEXT,
              ai_score REAL,
              ai_detail TEXT,
              collected_at INTEGER
            )
            """;
        jdbcTemplate.execute(sql);
        log.info("[LogRepository] log 테이블 초기화 완료 (없으면 생성)");
    }

    /**
     * 로그 한 건 INSERT.
     *
     * @param log DB에 저장할 Log 도메인 객체
     */
    public void insertLog(Log log) {
        String sql = """
            INSERT INTO log (
              owner_key, event_type, path,
              exists_flag, size, last_modified,
              hash, entropy,
              ai_label, ai_score, ai_detail,
              collected_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

        jdbcTemplate.update(sql,
                log.getOwnerKey(),
                log.getEventType(),
                log.getPath(),
                log.isExists() ? 1 : 0,
                log.getSize(),
                log.getLastModifiedTime(),
                log.getHash(),
                log.getEntropy(),
                log.getAiLabel(),
                log.getAiScore(),
                log.getAiDetail(),
                log.getCollectedAt() != null ? log.getCollectedAt().toEpochMilli() : null
        );
    }

    /**
     * 특정 ownerKey에 대한 최근 로그 N건 조회.
     *
     * @param ownerKey 사용자/세션 식별자
     * @param limit    최대 조회 개수
     * @return Log 리스트 (최신순)
     */
    public List<Log> findRecentLogsByOwner(String ownerKey, int limit) {
        String sql = """
            SELECT
              id, owner_key, event_type, path,
              exists_flag, size, last_modified,
              hash, entropy,
              ai_label, ai_score, ai_detail,
              collected_at
            FROM log
            WHERE owner_key = ?
            ORDER BY collected_at DESC, id DESC
            LIMIT ?
            """;

        return jdbcTemplate.query(sql, new Object[]{ownerKey, limit}, logRowMapper());
    }

    /**
     * ResultSet → Log 객체로 변환하는 RowMapper.
     */
    private RowMapper<Log> logRowMapper() {
        return new RowMapper<>() {
            @Override
            public Log mapRow(ResultSet rs, int rowNum) throws SQLException {
                return Log.builder()
                        .id(rs.getLong("id"))
                        .ownerKey(rs.getString("owner_key"))
                        .eventType(rs.getString("event_type"))
                        .path(rs.getString("path"))
                        .exists(rs.getInt("exists_flag") == 1)
                        .size(rs.getLong("size"))
                        .lastModifiedTime(rs.getLong("last_modified"))
                        .hash(rs.getString("hash"))
                        .entropy((Double) rs.getObject("entropy"))
                        .aiLabel(rs.getString("ai_label"))
                        .aiScore((Double) rs.getObject("ai_score"))
                        .aiDetail(rs.getString("ai_detail"))
                        .collectedAt(rs.getObject("collected_at") != null
                                ? Instant.ofEpochMilli(rs.getLong("collected_at"))
                                : null)
                        .build();
            }
        };
    }
}
