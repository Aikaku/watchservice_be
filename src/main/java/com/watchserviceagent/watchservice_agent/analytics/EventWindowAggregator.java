package com.watchserviceagent.watchservice_agent.analytics;

import com.watchserviceagent.watchservice_agent.ai.AiService;
import com.watchserviceagent.watchservice_agent.ai.domain.AiResult;
import com.watchserviceagent.watchservice_agent.ai.dto.AiPayload;
import com.watchserviceagent.watchservice_agent.collector.dto.FileAnalysisResult;
import com.watchserviceagent.watchservice_agent.storage.LogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;

/**
 * 여러 FileAnalysisResult 를 3초 윈도우로 묶어서
 * AI 서버에 보낼 9개 피처(AiPayload)를 집계하는 컴포넌트.
 *
 * 피처 정의:
 *  - file_read_count
 *      : "파일을 새로 연 것"으로 추정되는 횟수.
 *        - 같은 파일에 대해 첫 MODIFY 시점에서 +1
 *        - 이후 일정 시간(READ_SESSION_TIMEOUT_MS) 이내 MODIFY들은
 *          같은 세션으로 간주하고 read_count 는 증가시키지 않음.
 *
 *  - file_write_count
 *      : 파일 내용을 실제로 바꾸거나(rename 포함) 변경이 발생한 횟수.
 *        - MODIFY 중 (sizeDiff != 0 또는 entropyDiff != 0) 인 경우
 *        - DELETE+CREATE 로 탐지한 rename 1쌍당 +1
 *
 *  - file_delete_count
 *      : DELETE 이벤트 수.
 *        - 단, DELETE+CREATE 로 rename 으로 간주한 쌍은 제외.
 *
 *  - file_rename_count
 *      : 같은 윈도우 안에서 DELETE-CREATE 쌍으로 탐지한 rename-like 개수.
 *        - ownerKey 동일
 *        - 부모 디렉터리 동일
 *        - sizeBefore(DELETE) == sizeAfter(CREATE)
 *        - 확장자 동일(둘 다 null 이거나, 둘 다 있고 대소문자 무시 동일)
 *        - 두 이벤트 시간 차이 <= RENAME_MAX_GAP_MS
 *
 *  - file_encrypt_like_count
 *      : 엔트로피 증가 + (size 변경 또는 확장자 변경)인 암호화 의심 이벤트 수
 *
 *  - changed_files_count
 *      : 윈도우 동안 건드려진 서로 다른 파일 path 수.
 *        - 기본적으로 path 기반 unique 개수
 *        - 단, rename 1쌍당 1개로 취급 (oldPath + newPath 를 1개로 본다)
 *          → changed_files_count = (서로 다른 path 개수) - file_rename_count
 *
 *  - random_extension_flag
 *      : 화이트리스트 밖, 길이 >= 4, [a-z0-9] 로만 구성된 수상한 확장자가
 *        하나라도 등장하면 1, 아니면 0
 *
 *  - entropy_diff_mean
 *      : (after - before) 엔트로피 변화량 평균
 *
 *  - file_size_diff_mean
 *      : (after - before) 파일 크기 변화량 평균
 *
 * flush 시:
 *  - AiService.requestAnalysis(...) 로 AiResult 를 받아,
 *  - 윈도우 내 모든 FileAnalysisResult 에 AiResult 를 태워서
 *    LogService.saveAsync(...) 로 비동기 로그 저장.
 *
 * 추가 로그:
 *  - onFileAnalysisResult() 마다 현재 윈도우 집계 상태를 DEBUG 로그로 출력.
 *  - flushWindow() 시점에는 3초 윈도우 총집계를 INFO 로그로 출력.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class EventWindowAggregator {

    // 3초 윈도우
    private static final long WINDOW_MS = 3000L;

    // 한 파일을 "같이 연 상태"로 간주하는 최대 간격 (5분)
    private static final long READ_SESSION_TIMEOUT_MS = 5 * 60 * 1000L;

    // DELETE-CREATE 를 rename 으로 볼 때 허용하는 최대 시간 차이 (ms)
    private static final long RENAME_MAX_GAP_MS = 2000L;

    private final AiService aiService;
    private final LogService logService;

    // 현재 윈도우 시작 시각 (epoch millis)
    private Long currentWindowStartMs = null;

    // 현재 윈도우에 포함된 이벤트들
    private final List<FileAnalysisResult> currentEvents = new ArrayList<>();

    // ownerKey + "|" + path 기준으로 마지막 read(새 세션 시작) 시각
    private final Map<String, Long> lastReadTimeByFile = new HashMap<>();

    /**
     * 윈도우 집계 결과를 담는 내부 클래스.
     */
    private static class WindowStats {
        int fileReadCount;
        int fileWriteCount;
        int fileDeleteCount;
        int fileRenameCount;
        int fileEncryptLikeCount;
        int changedFilesCount;
        double entropyDiffMean;
        double sizeDiffMean;
        int randomExtensionFlag;

        @Override
        public String toString() {
            return "WindowStats{" +
                    "fileReadCount=" + fileReadCount +
                    ", fileWriteCount=" + fileWriteCount +
                    ", fileDeleteCount=" + fileDeleteCount +
                    ", fileRenameCount=" + fileRenameCount +
                    ", fileEncryptLikeCount=" + fileEncryptLikeCount +
                    ", changedFilesCount=" + changedFilesCount +
                    ", entropyDiffMean=" + entropyDiffMean +
                    ", sizeDiffMean=" + sizeDiffMean +
                    ", randomExtensionFlag=" + randomExtensionFlag +
                    '}';
        }
    }

    /**
     * Collector 에서 새 FileAnalysisResult 가 들어올 때마다 호출.
     */
    public synchronized void onFileAnalysisResult(FileAnalysisResult result) {
        long eventTimeMs = result.getEventTime() != null
                ? result.getEventTime().toEpochMilli()
                : System.currentTimeMillis();

        if (currentWindowStartMs == null) {
            // 첫 이벤트면 윈도우 시작
            currentWindowStartMs = eventTimeMs;
            log.debug("[EventWindowAggregator] 새 윈도우 시작: windowStartMs={}", currentWindowStartMs);
        } else {
            // 현재 이벤트가 3초 윈도우를 넘어서면 flush
            if (eventTimeMs - currentWindowStartMs >= WINDOW_MS) {
                log.debug("[EventWindowAggregator] 윈도우 경과 ({} ms) → flush 수행",
                        (eventTimeMs - currentWindowStartMs));
                flushWindow();
                currentWindowStartMs = eventTimeMs;
                log.debug("[EventWindowAggregator] 새로운 윈도우 시작: windowStartMs={}", currentWindowStartMs);
            }
        }

        // 이벤트 추가
        currentEvents.add(result);

        // === 현재 윈도우 집계 상태를 DEBUG 로그로 출력 ===
        WindowStats stats = computeWindowStats(
                currentEvents,
                new HashMap<>(lastReadTimeByFile),
                false // updateSessionState=false (시뮬레이션)
        );

        log.debug(
                "[EventWindowAggregator] 이벤트 수신 후 현재 윈도우 집계."
                        + " ownerKey={}, eventType={}, path={}"
                        + " | read={}, write={}, delete={}, rename={}, encryptLike={}, changedFiles={}, entropyDiffMean={}, sizeDiffMean={}, randomExt={}",
                result.getOwnerKey(),
                result.getEventType(),
                result.getPath(),
                stats.fileReadCount,
                stats.fileWriteCount,
                stats.fileDeleteCount,
                stats.fileRenameCount,
                stats.fileEncryptLikeCount,
                stats.changedFilesCount,
                stats.entropyDiffMean,
                stats.sizeDiffMean,
                stats.randomExtensionFlag
        );
    }

    /**
     * 필요 시, 외부에서 강제로 flush 하고 싶을 때 호출.
     * (예: Watcher 종료 시점 등)
     */
    public synchronized void flushIfNeeded() {
        if (!currentEvents.isEmpty()) {
            log.debug("[EventWindowAggregator] flushIfNeeded 호출 → 현재 윈도우 강제 flush");
            flushWindow();
            currentWindowStartMs = null;
        }
    }

    /**
     * 현재 윈도우의 이벤트들을 기반으로 9개 피처를 집계하고,
     * AiService 로 AI 분석을 요청한 뒤,
     * 각 이벤트에 AiResult 를 태워 Storage 로 넘긴다.
     */
    private void flushWindow() {
        if (currentEvents.isEmpty()) {
            return;
        }

        // ===== 1) 피처 집계 =====
        WindowStats stats = computeWindowStats(
                currentEvents,
                lastReadTimeByFile,
                true // updateSessionState=true (실제 세션 갱신)
        );

        AiPayload payload = AiPayload.builder()
                .fileReadCount(stats.fileReadCount)
                .fileWriteCount(stats.fileWriteCount)
                .fileDeleteCount(stats.fileDeleteCount)
                .fileRenameCount(stats.fileRenameCount)
                .fileEncryptLikeCount(stats.fileEncryptLikeCount)
                .changedFilesCount(stats.changedFilesCount)
                .randomExtensionFlag(stats.randomExtensionFlag)
                .entropyDiffMean(stats.entropyDiffMean)
                .fileSizeDiffMean(stats.sizeDiffMean)
                .build();

        Instant windowStart = Instant.ofEpochMilli(currentWindowStartMs);
        Instant windowEnd = currentEvents.get(currentEvents.size() - 1).getEventTime();
        if (windowEnd == null) {
            windowEnd = windowStart;
        }

        // === 3초 윈도우 총집계 로그 (INFO) ===
        log.info(
                "[EventWindowAggregator] 3초 윈도우 집계 완료."
                        + " windowStart={}, windowEnd={}"
                        + " | read={}, write={}, delete={}, rename={}, encryptLike={}, changedFiles={}, entropyDiffMean={}, sizeDiffMean={}, randomExt={}",
                windowStart,
                windowEnd,
                stats.fileReadCount,
                stats.fileWriteCount,
                stats.fileDeleteCount,
                stats.fileRenameCount,
                stats.fileEncryptLikeCount,
                stats.changedFilesCount,
                stats.entropyDiffMean,
                stats.sizeDiffMean,
                stats.randomExtensionFlag
        );

        // ===== 2) AI 분석 요청 =====
        AiResult aiResult = aiService.requestAnalysis(payload);
        log.info("[EventWindowAggregator] AI 분석 결과: {}", aiResult);

        // ===== 3) 윈도우 내 모든 이벤트에 AiResult 태워서 로그 저장 =====
        for (FileAnalysisResult r : currentEvents) {
            FileAnalysisResult enriched = r.withAiResult(aiResult);
            logService.saveAsync(enriched);
        }

        currentEvents.clear();
    }

    /**
     * 주어진 이벤트 리스트를 기반으로 윈도우 통계(WindowStats)를 계산.
     *
     * @param events              현재 윈도우 이벤트들
     * @param sessionState        ownerKey|path -> lastReadTimeMs 맵
     * @param updateSessionState  true 이면 세션 상태를 실제로 갱신,
     *                            false 이면 가상의 로컬 시뮬레이션만 수행.
     */
    private WindowStats computeWindowStats(
            List<FileAnalysisResult> events,
            Map<String, Long> sessionState,
            boolean updateSessionState
    ) {
        WindowStats stats = new WindowStats();

        int fileReadCount = 0;         // 새로 연 것으로 보는 MODIFY 개수
        int fileWriteCount = 0;        // 그 중 실제 내용이 바뀐 MODIFY 개수 + rename 개수
        int fileDeleteCount = 0;
        int fileEncryptLikeCount = 0;
        Set<String> changedFileSet = new HashSet<>();

        double entropyDiffSum = 0.0;
        double sizeDiffSum = 0.0;
        int entropyDiffCount = 0;
        int sizeDiffCount = 0;

        double encryptEntropyThreshold = 0.3;
        double eps = 1e-6;

        for (FileAnalysisResult r : events) {
            String eventType = r.getEventType();
            String path = r.getPath();
            String ownerKey = r.getOwnerKey();
            changedFileSet.add(path);

            Long sizeBefore = r.getSizeBefore();
            Long sizeAfter = r.getSizeAfter();
            Double entropyBefore = r.getEntropyBefore();
            Double entropyAfter = r.getEntropyAfter();

            long sizeDiff = 0L;
            if (sizeBefore != null && sizeAfter != null) {
                sizeDiff = sizeAfter - sizeBefore;
                sizeDiffSum += sizeDiff;
                sizeDiffCount++;
            }

            double entropyDiff = 0.0;
            if (entropyBefore != null && entropyAfter != null) {
                entropyDiff = entropyAfter - entropyBefore;
                entropyDiffSum += entropyDiff;
                entropyDiffCount++;
            }

            // MODIFY 이벤트: read_count, write_count 동시에 처리
            if ("MODIFY".equals(eventType)) {
                // ==== read_count: 새 세션인지 판단 ====
                long ts = (r.getEventTime() != null)
                        ? r.getEventTime().toEpochMilli()
                        : System.currentTimeMillis();
                String key = ownerKey + "|" + path;

                Long lastRead = sessionState.get(key);
                boolean isNewRead = (lastRead == null) ||
                        (ts - lastRead > READ_SESSION_TIMEOUT_MS);

                if (isNewRead) {
                    fileReadCount++;
                    // 실제/시뮬레이션 모두 로컬 sessionState 에는 반영
                    sessionState.put(key, ts);
                }

                // ==== write_count: 실제 내용이 바뀐 경우 ====
                boolean contentChanged = (sizeDiff != 0L) || (Math.abs(entropyDiff) > eps);
                if (contentChanged) {
                    fileWriteCount++;
                }
            }

            // DELETE 카운트
            if ("DELETE".equals(eventType)) {
                fileDeleteCount++;
            }

            // 암호화 의심 카운트
            boolean extChanged = (r.getExtBefore() != null && r.getExtAfter() != null
                    && !r.getExtBefore().equalsIgnoreCase(r.getExtAfter()));
            if (entropyDiff > encryptEntropyThreshold && (sizeDiff != 0L || extChanged)) {
                fileEncryptLikeCount++;
            }
        }

        // ===== rename-like 탐지 =====
        int fileRenameCount = detectRenameLikeCount(events);

        // DELETE 카운트에서 rename 에 해당하는 것만큼 빼기
        fileDeleteCount = Math.max(0, fileDeleteCount - fileRenameCount);

        // rename 1쌍당 write-like 변경 1회로 간주
        fileWriteCount += fileRenameCount;

        // changed_files_count: path 기준 unique 개수에서 rename 쌍만큼 보정
        int changedFilesCount = changedFileSet.size() - fileRenameCount;
        if (changedFilesCount < 0) {
            changedFilesCount = 0;
        }

        double entropyDiffMean = entropyDiffCount > 0 ? entropyDiffSum / entropyDiffCount : 0.0;
        double sizeDiffMean = sizeDiffCount > 0 ? sizeDiffSum / sizeDiffCount : 0.0;
        int randomExtensionFlag = detectRandomExtensionFlag(events);

        stats.fileReadCount = fileReadCount;
        stats.fileWriteCount = fileWriteCount;
        stats.fileDeleteCount = fileDeleteCount;
        stats.fileEncryptLikeCount = fileEncryptLikeCount;
        stats.changedFilesCount = changedFilesCount;
        stats.entropyDiffMean = entropyDiffMean;
        stats.sizeDiffMean = sizeDiffMean;
        stats.randomExtensionFlag = randomExtensionFlag;
        stats.fileRenameCount = fileRenameCount;

        // 실제 세션 상태 업데이트가 필요하면 sessionState(=lastReadTimeByFile)를 그대로 사용
        // (updateSessionState=false 일 때는 호출측에서 새 맵을 넘겨주므로, 여기서 따로 처리할 필요 없음)

        return stats;
    }

    /**
     * 랜덤 확장자 플래그 계산 (간단 버전).
     *
     * - 화이트리스트 밖, 길이 >= 4, [a-z0-9] 만으로 구성된 경우 있으면 1
     */
    private int detectRandomExtensionFlag(List<FileAnalysisResult> events) {
        String[] whiteList = {"txt", "log", "doc", "docx", "xls", "xlsx", "pdf",
                "png", "jpg", "jpeg", "gif", "zip", "rar", "7z"};

        for (FileAnalysisResult r : events) {
            String beforeExt = r.getExtBefore();
            String afterExt = r.getExtAfter();

            if (isSuspiciousExt(beforeExt, whiteList) || isSuspiciousExt(afterExt, whiteList)) {
                return 1;
            }
        }
        return 0;
    }

    private boolean isSuspiciousExt(String ext, String[] whiteList) {
        if (ext == null || ext.isBlank()) return false;

        String lower = ext.toLowerCase(Locale.ROOT);
        for (String w : whiteList) {
            if (w.equals(lower)) {
                return false;
            }
        }
        if (lower.length() < 4) return false;

        for (char c : lower.toCharArray()) {
            if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9'))) {
                return false;
            }
        }
        return true;
    }

    /**
     * DELETE-CREATE rename-like 이벤트를 탐지해서 개수를 반환.
     *
     * 기준:
     *  - ownerKey 동일
     *  - 부모 디렉터리 동일
     *  - sizeBefore(DELETE) == sizeAfter(CREATE) (둘 다 null 아니어야 함)
     *  - 확장자(extBefore/extAfter)가 같으면 rename 으로 인정
     *  - 두 이벤트 시간 차이 <= RENAME_MAX_GAP_MS
     *
     * 한 CREATE/DELETE 이벤트는 최대 한 번만 매칭된다.
     */
    private int detectRenameLikeCount(List<FileAnalysisResult> events) {
        List<FileAnalysisResult> deletes = new ArrayList<>();
        List<FileAnalysisResult> creates = new ArrayList<>();

        for (FileAnalysisResult r : events) {
            String eventType = r.getEventType();

            boolean isDelete = "DELETE".equals(eventType)
                    || (r.isExistsBefore() && !r.isExistsAfter());
            boolean isCreate = "CREATE".equals(eventType)
                    || (!r.isExistsBefore() && r.isExistsAfter());

            if (isDelete) {
                deletes.add(r);
            } else if (isCreate) {
                creates.add(r);
            }
        }

        if (deletes.isEmpty() || creates.isEmpty()) {
            return 0;
        }

        int renameCount = 0;
        boolean[] usedCreate = new boolean[creates.size()];

        for (FileAnalysisResult del : deletes) {
            String ownerKey = del.getOwnerKey();
            Long delSize = del.getSizeBefore();
            String delExt = del.getExtBefore();
            if (delSize == null) continue;

            String delParent = getParentDir(del.getPath());
            long delMs = del.getEventTime() != null
                    ? del.getEventTime().toEpochMilli()
                    : 0L;

            for (int i = 0; i < creates.size(); i++) {
                if (usedCreate[i]) continue;

                FileAnalysisResult crt = creates.get(i);
                if (!Objects.equals(ownerKey, crt.getOwnerKey())) {
                    continue;
                }

                String crtParent = getParentDir(crt.getPath());
                if (!Objects.equals(delParent, crtParent)) {
                    continue;
                }

                Long crtSize = crt.getSizeAfter();
                if (crtSize == null) continue;
                if (!delSize.equals(crtSize)) continue;

                String crtExt = crt.getExtAfter();
                if (delExt != null && crtExt != null &&
                        !delExt.equalsIgnoreCase(crtExt)) {
                    continue;
                }

                long crtMs = crt.getEventTime() != null
                        ? crt.getEventTime().toEpochMilli()
                        : 0L;

                long gap = Math.abs(crtMs - delMs);
                if (gap > RENAME_MAX_GAP_MS) {
                    continue;
                }

                // 여기까지 오면 rename-like 로 인정
                renameCount++;
                usedCreate[i] = true;
                break; // del 하나당 최대 한 개의 create 만 매칭
            }
        }

        return renameCount;
    }

    private String getParentDir(String path) {
        if (path == null) return null;
        int slash = path.lastIndexOf('/');
        int backslash = path.lastIndexOf('\\');
        int idx = Math.max(slash, backslash);
        if (idx < 0) {
            return "";
        }
        return path.substring(0, idx);
    }
}
