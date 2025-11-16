### 🚨 WatchService Agent

**WatchService Agent**는 지정한 폴더를 실시간으로 감시하고,  
파일 변경 이벤트 발생 시 자동으로 파일의 해시·엔트로피 등을 분석하여  
보안 이상 여부를 탐지하는 Spring Boot 기반 에이전트입니다.

---

### 🚨 주요 기능

- **폴더 감시 (Watcher)**: 파일 생성·수정·삭제 이벤트 감지
- **파일 분석 (Collector)**: 해시(SHA-256), 엔트로피 계산
- **로그 저장 (Storage)**: SQLite DB에 이벤트 로그 저장
- **AI 연동 (AI)**: AI 서버로 분석 결과 전송 후 위험도 판단

---

### 🚨구조

Watcher → Collector → Storage → AI  
        ↘ common ↗

---

| 도메인 | 역할 |
|--------|------|
| **watcher/** | 폴더 감시 및 이벤트 발생 처리 |
| **collector/** | 파일 분석 (해시·엔트로피 계산) |
| **storage/** | 로그 저장 및 관리 |
| **ai/** | AI 서버와의 통신 및 결과 처리 |
| **common/** | 공통 설정, 예외, 유틸리티 관리 |

---

### 🚨️ 실행 방법

```bash
# 빌드 및 실행
./gradlew build
./gradlew bootRun

