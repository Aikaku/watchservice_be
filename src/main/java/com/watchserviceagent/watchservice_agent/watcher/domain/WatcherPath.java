package com.watchserviceagent.watchservice_agent.watcher.domain;

import lombok.Builder;
import lombok.Data;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 감시 대상 경로(폴더 또는 파일)를 표현하는 도메인 모델.
 * - exists(): 파일 시스템 상에 실제 존재하는지
 * - isValid(): 감시 대상으로 유효한지(존재 + 디렉터리 or 일반 파일)
 */
@Data
@Builder
public class WatcherPath {

    /**
     * 감시 대상 경로 (절대/상대 상관 없음, 내부에서 항상 normalize해서 사용 권장)
     */
    private Path path;

    /**
     * 파일 시스템 상에 실제로 존재하는지 여부.
     * 경로가 null이면 false.
     */
    public boolean exists() {
        return path != null && Files.exists(path);
    }

    /**
     * 감시 대상으로 유효한지 여부.
     *
     * 조건:
     *  - path가 null이면 false
     *  - 실제 파일 시스템에 존재해야 함
     *  - 디렉터리이거나 일반 파일이어야 함 (심볼릭 링크 등은 필요 시 추가 처리)
     */
    public boolean isValid() {
        if (path == null) {
            return false;
        }
        Path normalized = path.toAbsolutePath().normalize();
        // 존재하면서, 디렉터리이거나 일반 파일이면 "감시 대상"으로 유효하다고 본다.
        return Files.exists(normalized)
                && (Files.isDirectory(normalized) || Files.isRegularFile(normalized));
    }
}
