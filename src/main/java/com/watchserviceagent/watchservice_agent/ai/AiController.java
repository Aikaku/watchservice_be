package com.watchserviceagent.watchservice_agent.ai;

import com.watchserviceagent.watchservice_agent.ai.domain.AiResult;
import com.watchserviceagent.watchservice_agent.ai.dto.AiPayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * AI 서버 연동을 직접 테스트하기 위한 컨트롤러.
 *
 * ✔ 핵심 파이프라인(Watcher → Collector → Aggregator → AiService)에는 필수는 아님.
 * ✔ 프론트/포스트맨에서 직접 AiPayload를 보내서
 *    - AI 서버가 잘 동작하는지
 *    - 현재 모델이 어떤 라벨/점수를 주는지
 *   확인하는 용도의 디버깅/테스트 엔드포인트.
 */
@RestController
@RequestMapping("/ai")
@RequiredArgsConstructor
@Slf4j
public class AiController {

    private final AiService aiService; // AI 서버 호출 서비스

    /**
     * AI 분석 테스트용 엔드포인트.
     *
     * POST /ai/analyze
     * Body(JSON) 예시:
     * {
     *   "fileWriteCount": 14,
     *   "fileRenameCount": 3,
     *   "fileDeleteCount": 1,
     *   "fileModifyCount": 14,
     *   "fileEncryptLikeCount": 2,
     *   "changedFilesCount": 18,
     *   "entropyDiffMean": 0.92,
     *   "fileSizeDiffMean": 134.5,
     *   "randomExtensionFlag": 1
     * }
     */
    @PostMapping("/analyze")
    public AiResult analyze(@RequestBody AiPayload payload) {
        log.info("[AiController] /ai/analyze 요청 수신: {}", payload);

        AiResult result = aiService.requestAnalysis(payload);

        log.info("[AiController] /ai/analyze 결과: {}", result);
        return result;
    }

    /**
     * 간단 헬스체크용 (원하면 프론트에서 ping 용도로 사용 가능).
     *
     * GET /ai/ping
     */
    @GetMapping("/ping")
    public String ping() {
        return "AI endpoint alive";
    }
}
