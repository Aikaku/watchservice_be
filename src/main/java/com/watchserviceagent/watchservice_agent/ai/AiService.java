package com.watchserviceagent.watchservice_agent.ai;

import com.watchserviceagent.watchservice_agent.ai.domain.AiResult;
import com.watchserviceagent.watchservice_agent.ai.dto.AiPayload;
import com.watchserviceagent.watchservice_agent.ai.dto.AiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

/**
 * AI 서버와 HTTP로 통신하는 서비스.
 *
 * - AiPayload 를 JSON 으로 보내고, AiResponse 를 받아서 AiResult 로 변환한다.
 * - 실제 URL/응답 형식은 Python/코랩 서버에 맞게 한 번만 수정하면 됨.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AiService {

    // 간단하게 RestTemplate 직접 생성 (필요하면 Bean 으로 분리 가능)
    private final RestTemplate restTemplate = new RestTemplate();

    // TODO: 실제 환경에 맞게 설정 파일(application.yml)에서 주입받도록 변경해도 됨.
    private static final String AI_SERVER_URL = "http://localhost:8000/api/analyze";

    /**
     * AI 서버에 분석을 요청하고 결과를 반환.
     *
     * @param payload 3초 윈도우 피처 벡터
     * @return AiResult (label/score/detail)
     */
    public AiResult requestAnalysis(AiPayload payload) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            HttpEntity<AiPayload> entity = new HttpEntity<>(payload, headers);

            log.debug("[AiService] 요청 전송: url={}, payload={}", AI_SERVER_URL, payload);

            AiResponse response = restTemplate.postForObject(
                    AI_SERVER_URL,
                    entity,
                    AiResponse.class
            );

            log.debug("[AiService] 응답 수신: {}", response);

            return AiResult.fromResponse(response);

        } catch (RestClientException e) {
            log.error("[AiService] AI 서버 호출 실패", e);
            return AiResult.error("AI 서버 호출 실패: " + e.getMessage());
        } catch (Exception e) {
            log.error("[AiService] 예기치 못한 예외", e);
            return AiResult.error("AI 호출 중 예외: " + e.getMessage());
        }
    }
}
