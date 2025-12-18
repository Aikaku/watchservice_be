package com.watchserviceagent.watchservice_agent.alerts;

import com.watchserviceagent.watchservice_agent.alerts.dto.AlertPageResponse;
import com.watchserviceagent.watchservice_agent.alerts.dto.AlertStatsResponse;
import com.watchserviceagent.watchservice_agent.common.util.SessionIdManager;
import com.watchserviceagent.watchservice_agent.storage.LogRepository;
import com.watchserviceagent.watchservice_agent.storage.domain.Log;
import com.watchserviceagent.watchservice_agent.storage.dto.LogResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;

/**
 * 클래스 이름 : AlertService
 * 기능 : 알림(위험 이벤트) 조회 및 통계를 처리하는 비즈니스 로직을 제공한다.
 * 작성 날짜 : 2025/12/17
 * 작성자 : 시스템
 */
@Service
@RequiredArgsConstructor
public class AlertService {

    private final SessionIdManager sessionIdManager;
    private final LogRepository logRepository;

    private static final int MAX_PAGE_SIZE = 1000;
    private static final DateTimeFormatter DATE_TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 함수 이름 : getAlerts
     * 기능 : 페이지네이션, 필터링, 정렬을 지원하는 알림 목록을 조회한다.
     * 매개변수 : page - 페이지 번호, size - 페이지 크기, from - 시작 날짜, to - 종료 날짜, level - 위험도 필터, keyword - 검색 키워드, sort - 정렬 기준
     * 반환값 : AlertPageResponse - 페이지네이션된 알림 목록
     * 작성 날짜 : 2025/12/17
     * 작성자 : 시스템
     */
    public AlertPageResponse getAlerts(
            Integer page,
            Integer size,
            String from,
            String to,
            String level,
            String keyword,
            String sort
    ) {
        int p = (page == null || page < 1) ? 1 : page;
        int s = (size == null || size < 1) ? 50 : Math.min(size, MAX_PAGE_SIZE);

        SortInfo si = parseSort(sort);

        Long fromEpoch = parseFromToEpochStart(from);
        Long toEpoch = parseFromToEpochEnd(to);

        String ownerKey = sessionIdManager.getSessionId();

        // level 정규화: ALL/빈값 => null 처리
        String lv = normalizeLevel(level);

        long total = logRepository.countAlertsByOwner(ownerKey, fromEpoch, toEpoch, keyword, lv);

        int offset = (p - 1) * s;
        List<Log> logs = logRepository.findAlertsByOwner(
                ownerKey, fromEpoch, toEpoch, keyword, lv,
                si.field, si.dir, offset, s
        );

        List<LogResponse> items = logs.stream().map(LogResponse::from).toList();

        return AlertPageResponse.builder()
                .items(items)
                .total(total)
                .page(p)
                .size(s)
                .build();
    }

    /**
     * 함수 이름 : getAlertById
     * 기능 : ID로 단일 알림의 상세 정보를 조회한다.
     * 매개변수 : id - 알림 ID
     * 반환값 : LogResponse - 알림 상세 정보
     * 예외 : NoSuchElementException - 알림을 찾을 수 없을 때
     * 작성 날짜 : 2025/12/17
     * 작성자 : 시스템
     */
    public LogResponse getAlertById(long id) {
        String ownerKey = sessionIdManager.getSessionId();
        Log logEntity = logRepository.findAlertByIdAndOwner(ownerKey, id)
                .orElseThrow(() -> new NoSuchElementException("alert not found: id=" + id));
        return LogResponse.from(logEntity);
    }

    /**
     * 함수 이름 : getStats
     * 기능 : 알림 통계를 일별 또는 주별로 조회한다.
     * 매개변수 : range - 통계 범위 (daily|weekly), from - 시작 날짜, to - 종료 날짜
     * 반환값 : AlertStatsResponse - 알림 통계 데이터
     * 작성 날짜 : 2025/12/17
     * 작성자 : 시스템
     */
    public AlertStatsResponse getStats(String range, String from, String to) {
        String rg = (range == null) ? "daily" : range.trim().toLowerCase(Locale.ROOT);
        if (!rg.equals("daily") && !rg.equals("weekly")) rg = "daily";

        Long fromEpoch = parseFromToEpochStart(from);
        Long toEpoch = parseFromToEpochEnd(to);

        String ownerKey = sessionIdManager.getSessionId();

        var rows = logRepository.getAlertStats(ownerKey, fromEpoch, toEpoch, rg);

        var series = rows.stream()
                .map(r -> AlertStatsResponse.SeriesPoint.builder()
                        .date(r.bucket())
                        .warning((int) r.warning())
                        .danger((int) r.danger())
                        .build())
                .toList();

        return AlertStatsResponse.builder()
                .range(rg)
                .from(from)
                .to(to)
                .series(series)
                .build();
    }

    private String normalizeLevel(String level) {
        if (level == null) return null;
        String v = level.trim().toUpperCase(Locale.ROOT);
        if (v.isBlank() || v.equals("ALL")) return null;
        if (v.equals("DANGER") || v.equals("WARNING") || v.equals("SAFE")) return v;
        return null;
    }

    private SortInfo parseSort(String sort) {
        if (sort == null || sort.isBlank()) return new SortInfo("collectedAt", "desc");
        String[] parts = sort.split(",");
        String field = parts.length > 0 ? parts[0].trim() : "collectedAt";
        String dir = parts.length > 1 ? parts[1].trim() : "desc";
        if (field.isBlank()) field = "collectedAt";
        if (dir.isBlank()) dir = "desc";
        return new SortInfo(field, dir);
    }

    private Long parseFromToEpochStart(String from) {
        if (from == null || from.isBlank()) return null;
        try {
            LocalDate d = LocalDate.parse(from.trim());
            return d.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (DateTimeParseException ignore) {}
        try {
            LocalDateTime dt = LocalDateTime.parse(from.trim(), DATE_TIME_FMT);
            return dt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (Exception ignore) {}
        return null;
    }

    private Long parseFromToEpochEnd(String to) {
        if (to == null || to.isBlank()) return null;
        try {
            LocalDate d = LocalDate.parse(to.trim());
            // inclusive end-of-day
            LocalDateTime end = d.atTime(23, 59, 59, 999_000_000);
            return end.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (DateTimeParseException ignore) {}
        try {
            LocalDateTime dt = LocalDateTime.parse(to.trim(), DATE_TIME_FMT);
            return dt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (Exception ignore) {}
        return null;
    }

    private static class SortInfo {
        final String field;
        final String dir;
        SortInfo(String field, String dir) { this.field = field; this.dir = dir; }
    }
}
