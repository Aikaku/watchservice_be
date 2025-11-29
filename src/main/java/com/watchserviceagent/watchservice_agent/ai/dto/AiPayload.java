package com.watchserviceagent.watchservice_agent.ai.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * AI 서버에 전송하는 윈도우 단위 통계 피처 페이로드.
 *
 * JSON 예시:
 * {
 *   "file_write_count": 14,
 *   "file_rename_count": 3,
 *   "file_delete_count": 1,
 *   "file_modify_count": 14,
 *   "file_encrypt_like_count": 2,
 *   "changed_files_count": 18,
 *   "entropy_diff_mean": 0.92,
 *   "file_size_diff_mean": 134.5,
 *   "random_extension_flag": 1
 * }
 *
 * - 각 필드는 "짧은 시간 윈도우(예: 1~3초)" 동안 관측된 통계량이다.
 * - random_extension_flag 는 랜덤 확장자 rename 패턴이 하나라도 발견되면 1, 아니면 0.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiPayload {

    /**
     * 해당 윈도우 동안 발생한 파일 write(실질적 수정) 이벤트 횟수.
     * Java NIO 기준으로는 ENTRY_MODIFY 이벤트 개수와 거의 동일하게 취급한다.
     */
    @JsonProperty("file_write_count")
    private int fileWriteCount;

    /**
     * rename 으로 추정되는 이벤트 횟수.
     * Java NIO 에서는 DELETE+CREATE 패턴을 휴리스틱하게 묶어서 추정한다.
     */
    @JsonProperty("file_rename_count")
    private int fileRenameCount;

    /**
     * 파일 삭제(ENTRY_DELETE) 이벤트 횟수.
     */
    @JsonProperty("file_delete_count")
    private int fileDeleteCount;

    /**
     * 파일 수정(modify) 이벤트 횟수.
     * write_count 와 엄밀히 분리하기 어렵기 때문에,
     * 구현 상으로는 ENTRY_MODIFY 기준으로 두 값이 동일해질 수 있다.
     */
    @JsonProperty("file_modify_count")
    private int fileModifyCount;

    /**
     * 암호화로 의심되는 파일 변화 패턴(엔트로피 상승 + 확장자 변경 + 크기 변화 등) 횟수.
     */
    @JsonProperty("file_encrypt_like_count")
    private int fileEncryptLikeCount;

    /**
     * 해당 윈도우 동안 변경(생성/수정/삭제/rename)된 전체 "서로 다른 파일 수".
     * = Changed/Created/Deleted/Renamed 에 등장한 unique path 개수.
     */
    @JsonProperty("changed_files_count")
    private int changedFilesCount;

    /**
     * 파일 변경 전/후 엔트로피 차이의 평균값.
     * (entropy_after - entropy_before) 를 윈도우 내 변경 파일들에 대해 평균낸 값.
     */
    @JsonProperty("entropy_diff_mean")
    private double entropyDiffMean;

    /**
     * 파일 크기 변화량의 평균값 (byte 단위).
     * (size_after - size_before) 를 윈도우 내 변경 파일들에 대해 평균낸 값.
     */
    @JsonProperty("file_size_diff_mean")
    private double fileSizeDiffMean;

    /**
     * 랜덤 확장자 패턴이 하나라도 관측되면 1, 아니면 0.
     * 예: .abcd, .xyz12 처럼 의미 없는 랜덤 문자열 확장자 패턴.
     */
    @JsonProperty("random_extension_flag")
    private int randomExtensionFlag;
}
