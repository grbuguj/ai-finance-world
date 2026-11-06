# ADR-0000. 개선 전에 측정 기반부터 만든다

- 상태: 채택
- 관련 코드: `PostingMetricsPort`, `MicrometerPostingMetricsAdapter`, `monitoring/`, `loadtest/`, `src/mysqlTest`

## 문제
"락 경합이 병목이다", "개선 후 빨라졌다" 같은 주장은 측정 없이는 검증할 수 없다.
또 기존 동시성 검증은 H2 에서만 이뤄져, 실제 운영 DB(MySQL InnoDB)의 락 동작과 다를 수 있었다.

## 결정
1. 기장 1건마다 **전체 처리시간**과 그 중 **행 락 대기시간**을 따로 기록한다 (Micrometer 히스토그램 → p99).
   락 대기를 분리해야 "느린 원인이 락 경합인지, 다른 곳인지"를 구분할 수 있다.
2. Prometheus 로 수집하고 Grafana 대시보드를 저장소에 코드로 둔다 (누구나 같은 화면 재현).
3. Testcontainers 로 실제 MySQL 8.0 을 띄워 기존 테스트를 그대로 재실행한다 (`./gradlew mysqlTest`).
   소스셋을 분리해 Docker 없는 환경에서도 `./gradlew test` 는 영향받지 않는다.
4. k6 시나리오 3종(송금 / 소비 핫스팟 / 혼합)으로 기준 수치를 측정한 뒤에 개선 작업을 시작한다.

## 결과
- 애플리케이션 계층은 Micrometer 를 직접 의존하지 않는다 (포트 뒤로 숨김).
- 이후 모든 개선은 `docs/results/` 에 같은 시나리오·같은 조건의 전후 수치로 기록한다.
