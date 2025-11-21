package com.watchserviceagent.watchservice_agent.collector.business;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 파일 내용의 Shannon 엔트로피를 계산하는 유틸.
 *
 * - 바이트 단위(0~255)의 분포를 기반으로 엔트로피를 계산한다.
 * - 엔트로피가 높을수록(8에 가까울수록) 내용이 "무작위에 가깝다"는 의미로 해석할 수 있고,
 *   일반 텍스트/문서보다 암호화/압축된 파일이 더 높은 값을 가지는 경향이 있다.
 *
 * 주의:
 * - 매우 큰 파일에 대해 전체를 다 읽으면 성능에 문제가 될 수 있으므로,
 *   최대 N바이트까지만 샘플링해서 계산하도록 구현했다.
 */
@Slf4j
@Component
public class EntropyAnalyzer {

    /**
     * 엔트로피 계산 시 최대 샘플링 바이트 수.
     * - 예) 1MB까지만 읽고 그 분포로 엔트로피 계산.
     * - 필요하면 나중에 설정값으로 뺄 수 있다.
     */
    private static final long MAX_SAMPLE_BYTES = 1L * 1024 * 1024; // 1MB

    /**
     * 주어진 파일의 Shannon 엔트로피를 계산한다.
     *
     * @param path 분석할 파일 경로
     * @return 엔트로피 값 (0.0 ~ 8.0 근처), 파일이 없거나 읽을 수 없으면 null
     */
    public Double calculateShannonEntropy(Path path) {
        if (path == null || !Files.isRegularFile(path)) {
            log.warn("[EntropyAnalyzer] 유효하지 않은 파일 경로: {}", path);
            return null;
        }

        long[] counts = new long[256];  // 각 바이트 값(0~255)의 등장 횟수
        long total = 0;                 // 실제로 읽은 바이트 수

        try (InputStream in = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1 && total < MAX_SAMPLE_BYTES) {
                int limit = (int) Math.min(read, MAX_SAMPLE_BYTES - total);
                for (int i = 0; i < limit; i++) {
                    int unsigned = buffer[i] & 0xFF;
                    counts[unsigned]++;
                }
                total += limit;
            }
        } catch (IOException e) {
            log.warn("[EntropyAnalyzer] 파일 읽기 중 예외 발생: {}", path, e);
            return null;
        }

        if (total == 0) {
            // 비어 있는 파일일 경우 엔트로피를 0.0으로 간주
            return 0.0;
        }

        // Shannon 엔트로피 H = - Σ p(i) * log2(p(i))
        double entropy = 0.0;
        for (long c : counts) {
            if (c == 0) continue;
            double p = (double) c / (double) total;
            entropy -= p * (log2(p));
        }

        return entropy;
    }

    /**
     * log2(x) = ln(x) / ln(2)
     */
    private double log2(double x) {
        return Math.log(x) / Math.log(2.0);
    }
}
