package com.watchserviceagent.watchservice_agent.dto.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.Instant;

/**
 * 클래스 이름 : WatchEventRecord
 * 기능 : 감시 이벤트를 표현하기 위한 클래스 (DTO)
 *       이벤트 종류, 파일/폴더 경로, 발생 시각을 포함하며
 *       toString() → JSON 문자열로 직렬화해서 로그 출력에 활용.
 * 작성 날짜 : 2025/09/30
 * 작성자 : 이상혁
 */
@Data
@AllArgsConstructor
public class WatchEventRecord {
    private String eventType;   // 이벤트 종류 (CREATE / MODIFY / DELETE)
    private String path;        // 파일/폴더 경로
    private Instant timestamp;  // 이벤트 발생 시각 (UTC)

    private static final ObjectMapper mapper = new ObjectMapper();

    @Override // toString 재정의
    public String toString() {
        try {
            return mapper.writeValueAsString(this); // -> mapper 재사용
            // return new ObjectMapper().writeValueAsString(this); -> mapper 매번 새로 생성
        } catch (JsonProcessingException e) {
            return "{ \"eventType\": \"" + eventType + "\", \"path\": \"" + path + "\" }";
        }
    }
}
