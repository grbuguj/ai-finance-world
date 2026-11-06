# 부하 테스트 (k6)

## 준비
```powershell
scoop install k6
docker-compose up -d mysql prometheus grafana
# 앱을 MySQL + loadtest 프로필로 실행
./gradlew bootRun --args='--spring.profiles.active=mysql,loadtest'
```
- Grafana: http://localhost:3000 → "AI Finance World — 거래 처리" 대시보드
- 매 실행마다 `setup` 단계에서 시뮬레이션을 정지하고 새 계좌 100개(각 1억)를 만든다.

## 실행
```powershell
k6 run -e LABEL=baseline-mysql loadtest/k6/transfer.js
k6 run -e LABEL=baseline-mysql loadtest/k6/spend-hotspot.js
k6 run -e LABEL=baseline-mysql loadtest/k6/mixed.js
```
| 환경변수 | 기본값 | 설명 |
|---|---|---|
| `VUS` | 50 | 동시 가상 사용자 수 |
| `DURATION` | 60s | 측정 시간 |
| `ACCOUNTS` | 100 | 생성할 계좌 수 |
| `LABEL` | local | 결과 파일 이름 (`results/<시나리오>-<LABEL>.json`) |

## 측정 후 확인
- `GET /integrity` 가 `balanced: true` 인지 (부하 후에도 원장 무결성 유지)
- 결과는 `docs/results/` 에 측정 조건과 함께 기록한다.
