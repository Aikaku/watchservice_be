package com.watchserviceagent.watchservice_agent.analytics;

import com.watchserviceagent.watchservice_agent.ai.dto.AiPayload;
import com.watchserviceagent.watchservice_agent.collector.dto.FileAnalysisResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;

/**
 * 짧은 시간 윈도우(예: 3초) 동안 들어온 파일 이벤트들을 모아서
 * AI 서버에 보낼 9개 피처를 집계하는 컴포넌트.
 *
 * 현재 버전은 실제 AI 호출 없이,
 * 윈도우가 flush 될 때 AiPayload 를 로그로만 출력한다.
 *
 * 피처 목록:
 *  - file_write_count
 *  - file_rename_count
 *  - file_delete_count
 *  - file_modify_count
 *  - file_encrypt_like_count
 *  - changed_files_count
 *  - entropy_diff_mean
 *  - file_size_diff_mean
 *  - random_extension_flag
 */
@Component
@Slf4j
public class EventWindowAggregator {

    /** 윈도우 길이 (ms 단위). 예: 3000ms = 3초 */
    private static final long WINDOW_MILLIS = 3000L;

    /** DELETE 후 CREATE 가 몇 ms 이내면 rename 으로 볼지 기준 */
    private static final long RENAME_TIME_WINDOW_MILLIS = 1000L;

    /** "랜덤 확장자"로 간주하지 않을 일반적인 확장자 화이트리스트 */
    private static final Set<String> COMMON_EXT_WHITELIST = Set.of(
            "txt", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
            "pdf", "jpg", "jpeg", "png", "gif", "bmp",
            "mp3", "wav", "mp4", "avi", "mkv",
            "zip", "rar", "7z",
            "exe", "dll", "sys", "ini", "log",
            "csv", "json", "xml"
    );

    // ===== 윈도우 메타 정보 =====

    private long windowStartMillis = -1L;
    private long windowEndMillis = -1L;
    private boolean hasActiveWindow = false;

    // ===== 카운트/합계 =====

    /** 실제로 "내용이 바뀐" MODIFY (write) 횟수 */
    private int fileWriteCount = 0;

    /** rename-like 패턴(DETELE+CREATE) 횟수 */
    private int fileRenameCount = 0;

    /** DELETE 이벤트 횟수 */
    private int fileDeleteCount = 0;

    /** OS 기준 MODIFY 이벤트 횟수 (내용/메타데이터 포함) */
    private int fileModifyCount = 0;

    /** 암호화 의심 패턴 횟수 */
    private int fileEncryptLikeCount = 0;

    /** 엔트로피 diff 합계 및 개수 */
    private double entropyDiffSum = 0.0;
    private int entropyDiffCount = 0;

    /** 파일 크기 diff 합계 및 개수 */
    private long sizeDiffSum = 0L;
    private int sizeDiffCount = 0;

    /** 윈도우 동안 변경된 "서로 다른 파일 경로" 집합 */
    private final Set<String> changedPaths = new HashSet<>();

    /** 랜덤스러운 확장자가 한 번이라도 등장하면 true */
    private boolean randomExtensionFlag = false;

    /** rename-like 추정을 위해 최근 DELETE 이벤트를 보관 (디렉토리 단위) */
    private final Map<String, DeleteEventInfo> recentDeleteMap = new HashMap<>();

    // ===== public API =====

    /**
     * Collector 에서 계산된 FileAnalysisResult 를 받아
     * 현재 윈도우에 반영한다.
     */
    public synchronized void onFileAnalysisResult(FileAnalysisResult result) {
        if (result == null) {
            return;
        }

        Instant t = result.getEventTime();
        if (t == null) {
            t = Instant.now();
        }
        long ts = t.toEpochMilli();

        // 1) 윈도우 관리 (새 윈도우 시작 / 기존 윈도우 유지 / 윈도우 flush)
        if (!hasActiveWindow) {
            openNewWindow(ts);
        } else if (ts - windowStartMillis >= WINDOW_MILLIS) {
            // 현재 이벤트 타임스탬프가 윈도우 범위를 넘어섰으면 flush 후 새 윈도우 시작
            flushCurrentWindow();
            openNewWindow(ts);
        }

        // 2) 현재 윈도우에 이벤트 반영
        updateStatsWith(result, ts);
    }

    /**
     * 감시 중지 등으로 인해 남아 있는 마지막 윈도우를 강제로 flush 하고 싶을 때 사용.
     */
    public synchronized void flushIfNeeded() {
        if (hasActiveWindow) {
            flushCurrentWindow();
            resetWindow();
        }
    }

    // ===== 내부 구현 =====

    private void openNewWindow(long startMillis) {
        resetWindow();
        this.windowStartMillis = startMillis;
        this.windowEndMillis = startMillis + WINDOW_MILLIS;
        this.hasActiveWindow = true;
        log.debug("Open new window: start={} end={}",
                Instant.ofEpochMilli(windowStartMillis),
                Instant.ofEpochMilli(windowEndMillis));
    }

    private void resetWindow() {
        windowStartMillis = -1L;
        windowEndMillis = -1L;
        hasActiveWindow = false;

        fileWriteCount = 0;
        fileRenameCount = 0;
        fileDeleteCount = 0;
        fileModifyCount = 0;
        fileEncryptLikeCount = 0;

        entropyDiffSum = 0.0;
        entropyDiffCount = 0;

        sizeDiffSum = 0L;
        sizeDiffCount = 0;

        changedPaths.clear();
        randomExtensionFlag = false;
        recentDeleteMap.clear();
    }

    /**
     * FileAnalysisResult 한 건을 현재 윈도우 통계에 반영하는 로직.
     *
     * 여기서 write/modify 를 분리해서 계산한다.
     */
    private void updateStatsWith(FileAnalysisResult r, long ts) {
        String eventType = safeUpper(r.getEventType());
        String path = r.getPath();
        if (path != null) {
            changedPaths.add(path);
        }

        // 1) 기본 카운트: delete / modify
        if ("DELETE".equals(eventType)) {
            fileDeleteCount++;
        } else if ("MODIFY".equals(eventType)) {
            // OS 기준 MODIFY 이벤트는 무조건 modify_count 에 반영
            fileModifyCount++;
        } else if ("CREATE".equals(eventType)) {
            // CREATE 자체로는 write/modify 카운트는 증가시키지 않는다.
        }

        // 2) DELETE → CREATE 패턴으로 rename-like 추정
        handleRenameHeuristic(r, ts, eventType);

        // 3) entropy_diff_mean, file_size_diff_mean 계산용 합계/개수 갱신
        Double eBefore = r.getEntropyBefore();
        Double eAfter = r.getEntropyAfter();
        Long sBefore = r.getSizeBefore();
        Long sAfter = r.getSizeAfter();

        double entropyDiff = 0.0;
        boolean hasEntropyDiff = false;

        if (eBefore != null && eAfter != null) {
            entropyDiff = r.getEntropyDiff(); // after - before
            entropyDiffSum += entropyDiff;
            entropyDiffCount++;
            hasEntropyDiff = true;
        }

        long sizeDiff = 0L;
        boolean hasSizeDiff = false;

        if (sBefore != null && sAfter != null) {
            sizeDiff = r.getSizeDiff(); // after - before
            sizeDiffSum += sizeDiff;
            sizeDiffCount++;
            hasSizeDiff = true;
        }

        // 4) write_count 판단:
        //    - OS 관점: MODIFY 인 이벤트 중에서
        //    - 내용이 실제로 바뀐 경우만 write 로 간주
        //      → sizeDiff != 0 또는 |entropyDiff| > 작은 threshold
        if ("MODIFY".equals(eventType)) {
            boolean sizeChanged = hasSizeDiff && (sizeDiff != 0L);
            boolean entropyChanged = hasEntropyDiff && (Math.abs(entropyDiff) > 0.01); // 매우 작은 변화는 noise 로 무시

            if (sizeChanged || entropyChanged) {
                fileWriteCount++;
            }
        }

        // 5) encrypt-like 패턴 및 random extension 플래그 판단
        handleEncryptLikeAndRandomExt(r);
    }

    /**
     * 현재 윈도우를 flush 해서 AiPayload 를 만들고,
     * 로그로 출력한다. (AI 서버 호출 없음)
     */
    private void flushCurrentWindow() {
        if (!hasActiveWindow) {
            return;
        }

        int changedFilesCount = changedPaths.size();

        double entropyDiffMean = (entropyDiffCount > 0)
                ? (entropyDiffSum / entropyDiffCount)
                : 0.0;

        double fileSizeDiffMean = (sizeDiffCount > 0)
                ? ((double) sizeDiffSum / (double) sizeDiffCount)
                : 0.0;

        AiPayload payload = AiPayload.builder()
                .fileWriteCount(fileWriteCount)
                .fileRenameCount(fileRenameCount)
                .fileDeleteCount(fileDeleteCount)
                .fileModifyCount(fileModifyCount)
                .fileEncryptLikeCount(fileEncryptLikeCount)
                .changedFilesCount(changedFilesCount)
                .entropyDiffMean(entropyDiffMean)
                .fileSizeDiffMean(fileSizeDiffMean)
                .randomExtensionFlag(randomExtensionFlag ? 1 : 0)
                .build();

        log.info("[WINDOW] {} ~ {} -> AiPayload={}",
                Instant.ofEpochMilli(windowStartMillis),
                Instant.ofEpochMilli(windowEndMillis),
                payload);
    }

    // ===== rename 추정 로직 =====

    private void handleRenameHeuristic(FileAnalysisResult r, long ts, String eventType) {
        String path = r.getPath();
        if (path == null) return;

        String dir = extractDir(path);

        if ("DELETE".equals(eventType)) {
            // 최근 DELETE 이벤트 기록
            DeleteEventInfo info = new DeleteEventInfo(
                    path,
                    r.getSizeBefore(),
                    r.getExtBefore(),
                    ts
            );
            recentDeleteMap.put(dir, info);
        } else if ("CREATE".equals(eventType)) {
            // 같은 디렉토리 내에서 최근 DELETE 가 있고, 시간/크기가 유사하면 rename 으로 간주
            DeleteEventInfo del = recentDeleteMap.get(dir);
            if (del != null) {
                if (ts - del.eventTimeMs <= RENAME_TIME_WINDOW_MILLIS) {
                    Long sizeBefore = del.sizeBefore;
                    Long sizeAfter = r.getSizeAfter();
                    if (sizeBefore != null && sizeAfter != null) {
                        long diff = Math.abs(sizeAfter - sizeBefore);
                        long maxSize = Math.max(sizeAfter, sizeBefore);
                        // 크기 차이가 전체의 10% 이하이면 rename 으로 추정
                        boolean similarSize = (maxSize == 0L) || (diff <= maxSize * 0.1);

                        if (similarSize) {
                            fileRenameCount++;
                            // rename 으로 매칭된 DELETE 는 제거
                            recentDeleteMap.remove(dir);
                            log.debug("Detected rename-like pattern: {} -> {} (dir={})",
                                    del.oldPath, path, dir);
                        }
                    }
                }
            }
        }
    }

    // ===== encrypt-like & random extension 판단 =====

    private void handleEncryptLikeAndRandomExt(FileAnalysisResult r) {
        Double eBefore = r.getEntropyBefore();
        Double eAfter = r.getEntropyAfter();
        Long sBefore = r.getSizeBefore();
        Long sAfter = r.getSizeAfter();
        String extBefore = safeLower(r.getExtBefore());
        String extAfter = safeLower(r.getExtAfter());

        // 랜덤 확장자로 보이는 경우 플래그 세팅
        if (extAfter != null && extBefore != null && !extBefore.equals(extAfter)) {
            if (looksLikeRandomExtension(extAfter)) {
                randomExtensionFlag = true;
            }
        }

        if (eBefore != null && eAfter != null && sBefore != null && sAfter != null) {
            double entropyDiff = eAfter - eBefore;
            long sizeDiff = sAfter - sBefore;

            boolean entropyIncrease = entropyDiff > 0.3;        // TODO: threshold 튜닝 가능
            boolean sizeChanged = Math.abs(sizeDiff) > 1024L;   // 1KB 이상 변화
            boolean extChanged = (extBefore != null && extAfter != null && !extBefore.equals(extAfter));

            // 간단한 encrypt-like 휴리스틱:
            //  - 엔트로피가 꽤 증가하고
            //  - 크기 변화나 확장자 변경이 동반되면 암호화 의심
            if (entropyIncrease && (sizeChanged || extChanged)) {
                fileEncryptLikeCount++;
            }
        }
    }

    // ===== 헬퍼 메서드들 =====

    private String extractDir(String path) {
        int idx = path.lastIndexOf('/');
        if (idx < 0) {
            idx = path.lastIndexOf('\\');
        }
        if (idx < 0) return "";
        return path.substring(0, idx);
    }

    private boolean looksLikeRandomExtension(String ext) {
        if (ext == null) return false;
        String lower = ext.toLowerCase(Locale.ROOT);

        // 잘 알려진 확장자는 랜덤으로 보지 않는다.
        if (COMMON_EXT_WHITELIST.contains(lower)) {
            return false;
        }

        // 길이가 너무 짧으면 (.c, .h 등) 랜덤이라 보기 애매
        if (lower.length() < 4) {
            return false;
        }

        // 알파벳/숫자 이외 문자가 섞여 있으면 랜덤으로 보지 않는다.
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (!(c >= 'a' && c <= 'z') && !(c >= '0' && c <= '9')) {
                return false;
            }
        }

        // 위 조건을 통과하면 "랜덤 확장자일 가능성이 있다"고 보고 true
        return true;
    }

    private String safeUpper(String s) {
        return (s == null) ? null : s.toUpperCase(Locale.ROOT);
    }

    private String safeLower(String s) {
        return (s == null) ? null : s.toLowerCase(Locale.ROOT);
    }

    // 최근 DELETE 이벤트 정보
    private static class DeleteEventInfo {
        final String oldPath;
        final Long sizeBefore;
        final String extBefore;
        final long eventTimeMs;

        DeleteEventInfo(String oldPath, Long sizeBefore, String extBefore, long eventTimeMs) {
            this.oldPath = oldPath;
            this.sizeBefore = sizeBefore;
            this.extBefore = extBefore;
            this.eventTimeMs = eventTimeMs;
        }
    }
}
