# 실험 보고서 — 락 전략 비교와 낙관적 락 Lost Update

- 실험 코드: `src/test/java/.../LockStrategyBenchmark.java` (MySQL 변형: `MySqlLockStrategyBenchmark`)
- 실행 환경: GitHub Actions `ubuntu-latest` (4 vCPU), Java 17 (Temurin), **MySQL 8.0.46 (Testcontainers), REPEATABLE-READ**
- 실행: `./gradlew benchmark` (H2) / `./gradlew mysqlBenchmark` (MySQL) — CI 의 `Lock strategy benchmark` 잡에서 매 push 마다 재실행
- 공통 조건: 워커 스레드 32, 커넥션 풀 32, 송금액 100원 고정, 실행마다 새 계좌 생성, 예열 실행 별도(예열 결과의 무결성 위반도 보고)

## 1. 무엇을 비교했나

모든 전략이 **같은 원장 기록**(거래 1건 + DEBIT/CREDIT 분개 2건)을 남기고, 잔액을 바꾸는 방법만 다르다.

| 전략 | 잔액 갱신 방식 | 동시성 제어 |
|---|---|---|
| `PESSIMISTIC` (서비스 채택) | `SELECT … FOR UPDATE` (계좌 ID 오름차순) → 검증 → `UPDATE balance = balance ± ?` | 행 락 대기 |
| `OPTIMISTIC` | `SELECT balance, version` → `UPDATE balance = <읽은 값 ± 100>, version = version + 1 WHERE version = ?` | 충돌 시 재시도(최대 30회, 지수 백오프 + 지터) |
| `OPTIMISTIC_DELTA` | 위와 같으나 `UPDATE balance = balance ± ?` (상대값) — **대조군** | 〃 |
| `CONDITIONAL` | 읽지 않고 `UPDATE balance = balance - ? WHERE id = ? AND balance >= ?` | 행 락 (단일 문장, 원자적) |

| 시나리오 | 의미 |
|---|---|
| `UNIFORM` | 100개 계좌 사이 무작위 송금 — 경합 낮음 |
| `HOTSPOT` | 200개 계좌 → **1개 계좌**로 집중 (가맹점 결제 몰림) |
| `HOTSPOT_SHARD` | 200개 계좌 → 8개 서브계좌로 분산 (핫스팟 완화책) |
| `PINGPONG` | 두 계좌가 서로에게 동시에 송금 — 최대 경합, 데드락 유발 패턴 |

모든 실행 후 **그 실행에 쓴 계좌**에 대해 `잔액 = ∑CREDIT − ∑DEBIT` 과 총액 보존을 확인한다(무결성 열).

## 2. 결과 — MySQL 8.0 (실행당 2,000건)

| 시나리오 | 전략 | 성공/요청 | 재시도 | TPS | p50 | p95 | p99 | 무결성 |
|---|---|---:|---:|---:|---:|---:|---:|:-:|
| UNIFORM | PESSIMISTIC | 2000/2000 | 0 | 1116 | 24.23 | 59.42 | 80.38 | ✅ |
| UNIFORM | OPTIMISTIC | 2000/2000 | 1685 | 994 | 21.39 | 86.90 | 155.65 | ✅ |
| UNIFORM | OPTIMISTIC_DELTA | 2000/2000 | 1682 | 1247 | 16.11 | 70.46 | 146.15 | ✅ |
| UNIFORM | CONDITIONAL | 2000/2000 | 0 | 1786 | 14.36 | 39.95 | 55.89 | ✅ |
| HOTSPOT | PESSIMISTIC | 2000/2000 | 0 | 731 | 42.88 | 48.69 | 50.44 | ✅ |
| HOTSPOT | OPTIMISTIC | 1894/2000 | 10039 | 187 | 12.89 | 578.39 | 822.12 | ❌ |
| HOTSPOT | OPTIMISTIC_DELTA | 1835/2000 | 10626 | 136 | 16.85 | 680.19 | 883.75 | ✅ |
| HOTSPOT | CONDITIONAL | 2000/2000 | 0 | 844 | 36.29 | 42.37 | 76.04 | ✅ |
| HOTSPOT_SHARD | PESSIMISTIC | 2000/2000 | 0 | 1707 | 15.42 | 44.69 | 56.18 | ✅ |
| HOTSPOT_SHARD | OPTIMISTIC | 1988/2000 | 3790 | 499 | 13.55 | 272.08 | 760.47 | ✅ |
| HOTSPOT_SHARD | OPTIMISTIC_DELTA | 1983/2000 | 3399 | 492 | 10.53 | 290.01 | 839.54 | ✅ |
| HOTSPOT_SHARD | CONDITIONAL | 2000/2000 | 0 | 1663 | 14.02 | 50.75 | 74.65 | ✅ |
| PINGPONG | PESSIMISTIC | 2000/2000 | 0 | 719 | 44.14 | 47.07 | 50.27 | ✅ |
| PINGPONG | OPTIMISTIC | 1981/2000 | 7542 | 458 | 2.64 | 351.31 | 601.24 | ✅ |
| PINGPONG | OPTIMISTIC_DELTA | 1973/2000 | 7352 | 445 | 2.55 | 363.04 | 567.02 | ✅ |
| PINGPONG | CONDITIONAL | 2000/2000 | 0 | 836 | 37.64 | 41.32 | 48.79 | ✅ |

<details><summary>H2 인메모리 (실행당 1,000건) — 참고용</summary>

| 시나리오 | 전략 | 성공/요청 | 재시도 | TPS | p50 | p95 | p99 | 무결성 |
|---|---|---:|---:|---:|---:|---:|---:|:-:|
| UNIFORM | PESSIMISTIC | 1000/1000 | 0 | 5029 | 0.47 | 20.77 | 44.34 | ✅ |
| UNIFORM | OPTIMISTIC | 1000/1000 | 182 | 7764 | 0.24 | 16.69 | 32.92 | ✅ |
| UNIFORM | OPTIMISTIC_DELTA | 1000/1000 | 137 | 8895 | 0.18 | 14.88 | 28.83 | ✅ |
| UNIFORM | CONDITIONAL | 1000/1000 | 0 | 18874 | 0.08 | 7.24 | 13.08 | ✅ |
| HOTSPOT | PESSIMISTIC | 1000/1000 | 0 | 9970 | 0.66 | 11.71 | 27.50 | ✅ |
| HOTSPOT | OPTIMISTIC | 1000/1000 | 534 | 6784 | 0.07 | 13.15 | 73.81 | ✅ |
| HOTSPOT | OPTIMISTIC_DELTA | 1000/1000 | 401 | 8851 | 0.06 | 10.44 | 65.29 | ✅ |
| HOTSPOT | CONDITIONAL | 1000/1000 | 0 | 21216 | 0.06 | 8.04 | 20.67 | ✅ |
| HOTSPOT_SHARD | PESSIMISTIC | 1000/1000 | 0 | 24199 | 0.07 | 4.62 | 8.01 | ✅ |
| HOTSPOT_SHARD | OPTIMISTIC | 1000/1000 | 174 | 21754 | 0.05 | 7.43 | 14.91 | ✅ |
| HOTSPOT_SHARD | OPTIMISTIC_DELTA | 1000/1000 | 94 | 24862 | 0.05 | 3.86 | 11.67 | ✅ |
| HOTSPOT_SHARD | CONDITIONAL | 1000/1000 | 0 | 31378 | 0.04 | 1.97 | 9.49 | ✅ |
| PINGPONG | PESSIMISTIC | 1000/1000 | 0 | 17861 | 0.65 | 7.41 | 13.88 | ✅ |
| PINGPONG | OPTIMISTIC | 1000/1000 | 350 | 8550 | 0.04 | 6.09 | 51.81 | ✅ |
| PINGPONG | OPTIMISTIC_DELTA | 1000/1000 | 418 | 7348 | 0.04 | 9.15 | 69.38 | ✅ |
| PINGPONG | CONDITIONAL | 1000/1000 | 0 | 26689 | 0.59 | 4.36 | 8.83 | ✅ |

H2 는 같은 JVM 안의 메모리 DB 라 네트워크·디스크 비용이 없어 절대 수치는 MySQL 과 비교할 수 없다. 전략 간 상대 비교와 정합성 확인 용도.
</details>

## 3. 해석

1. **비관적 락은 경합이 심할수록 예측 가능하다.** HOTSPOT·PINGPONG 에서 p99 가 50ms 안팎으로 p50 과 차이가 작다.
   대기열에 줄을 서기 때문에 느려도 **꼬리 지연이 튀지 않고 실패가 0건**이다.
2. **낙관적 락은 경합이 낮을 때만 이득이다.** UNIFORM 에서는 비슷하지만 HOTSPOT 에서 TPS 가 비관적 락의 1/4 수준(187 vs 731)으로 떨어지고,
   p99 가 822ms 로 16배 튀며, 재시도 한도(30회)를 넘겨 **요청의 5% 가 실패**한다. 금융 거래에서 "다시 시도하세요"는 사용자 경험 비용이다.
3. **조건부 UPDATE 가 가장 빠르다.** 읽기 왕복과 별도 락 획득이 없어서 UNIFORM 에서 비관적 락보다 1.6배 빠르다.
   하지만 "잔액 ≥ 금액" 같은 **단일 행 조건만** 표현할 수 있다. 한도 검증·여러 계좌 규칙·도메인 객체의 규칙 메서드(`Account.withdraw`)를 쓸 수 없어서,
   규칙이 SQL 에 흩어진다. → 서비스는 비관적 락을 유지하고, 조건부 UPDATE 는 "단순 적립/차감 전용 경로"의 최적화 후보로 남긴다.
4. **핫스팟은 락 방식보다 데이터 분산이 더 효과적이다.** 같은 비관적 락에서 수신 계좌를 8개 서브계좌로 나누면
   TPS 731 → 1,707 (**2.3배**), p50 42.9ms → 15.4ms. 가맹점 계좌처럼 쓰기가 몰리는 계좌는 서브계좌로 나누고, 조회 시 합산하는 설계가 유효하다.

## 4. 발견 — 절대값 낙관적 락의 Lost Update (MySQL 에서만 관측)

### 증상
`HOTSPOT / OPTIMISTIC` 실행에서 수신 계좌 1개의 잔액이 원장보다 정확히 100원 적었다. 버전은 커밋 수만큼 올라갔는데 잔액 증가가 한 번 빠졌다.
H2 에서는 한 번도 나오지 않았다.

### 추적 방법 — 감사(audit) 사슬
낙관적 락 트랜잭션이 커밋될 때 **같은 트랜잭션 안에서** `(읽은 버전, 읽은 잔액, 쓴 잔액, 커넥션 id, 격리 수준)` 을 감사 테이블에 남겼다.
커밋된 것만 남으므로, 버전 순으로 이으면 `읽은 잔액(n+1) = 쓴 잔액(n)` 이 반드시 성립해야 한다.

```
prev: conn 9   read_version=2     read_balance=101.00     → write 201.00
cur : conn 18  read_version=3     read_balance=101.00     → write 201.00      ← 사슬 단절
```
`cur` 가 읽은 `(잔액 101, 버전 3)` 은 **커밋된 적 없는 조합**이다(버전 3 의 잔액은 201). 그 낡은 잔액에 100 을 더해 **절대값으로 덮어쓰면서**
`prev` 의 +100 이 사라졌다. 세 번의 단절이 모두 같은 모양 — 직전 커밋의 *새 버전*과 *이전 잔액*을 함께 읽음 — 이었다.

### 대조 실험 (HOTSPOT·HOTSPOT_SHARD × 6회, MySQL 8.0.46, REPEATABLE-READ)

| 구현 | 커밋 | 사슬 단절 | 잔액≠원장 계좌 |
|---|---:|---:|---:|
| `OPTIMISTIC` (읽은 잔액으로 덮어쓰기) | 22,973 | **3** | **3** |
| `OPTIMISTIC_DELTA` (`balance = balance ± ?`) | 22,989 | 0 | 0 |

같은 SELECT 로 읽고 같은 버전 조건을 쓰는데, 잔액을 **상대값**으로 갱신한 대조군에서는 사슬 단절·불일치가 한 건도 없었다.

### 결론과 설계 원칙
- 관측된 사실: *"스냅샷에서 읽은 값을 그대로 다시 써 넣는"* 패턴은 이 환경에서 약 7,600 커밋당 1건 꼴로 돈을 잃었다.
  같은 조건의 비관적 락·조건부 UPDATE·상대값 낙관적 락은 0건.
- InnoDB 내부에서 왜 그 조합이 읽혔는지(MVCC 재구성·락 대기 후 재평가 경로 등)는 **아직 규명하지 못했다** — 후속 과제.
  이 보고서는 원인을 추정해 단정하지 않고, 재현 코드·감사 데이터·대조 결과만 근거로 삼는다.
- 그럼에도 설계 원칙은 분명하다. **돈은 "읽은 값을 덮어쓰지" 말고 "DB 안에서 더하고 빼라"** (`balance = balance ± ?`),
  그리고 잔액 필드는 항상 **원장으로 재검증**하라. 이 프로젝트의 무결성 검증(③ 잔액 = 원장)이 바로 이 문제를 잡아냈다.
- 서비스 경로(`LedgerPostingService`)는 비관적 락 + 도메인 객체 갱신이며, 위 실험의 모든 시나리오·예열에서 무결성 위반 0건이다.
  추가 방어로 `Account` 에 `@Version` 을 두어, 락을 우회한 갱신이 다른 갱신을 덮어쓰면 예외가 나도록 했다.

## 5. 재현
```bash
./gradlew mysqlBenchmark          # Docker 필요. 결과: build/benchmark/lock-strategy-mysql.md
# 표준출력 BENCH| / BENCH-DIAG| / AUDIT-SUMMARY| / AUDIT-BREAK| 줄에 원시 데이터
```
수치는 CI 러너(공유 VM)라 실행마다 ±20% 정도 흔들린다. 전략 간 순위와 무결성 결과는 매 실행 동일했다.
