# AI Finance World


[![CI](https://github.com/grbuguj/ai-finance-world/actions/workflows/ci.yml/badge.svg)](https://github.com/grbuguj/ai-finance-world/actions/workflows/ci.yml)
![Java](https://img.shields.io/badge/Java-17-007396?style=flat&logo=openjdk&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring_Boot-4.0-6DB33F?style=flat&logo=springboot&logoColor=white)
![MySQL](https://img.shields.io/badge/MySQL-8.0-4479A1?style=flat&logo=mysql&logoColor=white)
![Redis](https://img.shields.io/badge/Redis-latest-DC382D?style=flat&logo=redis&logoColor=white)
![Kafka](https://img.shields.io/badge/Apache_Kafka-latest-231F20?style=flat&logo=apachekafka&logoColor=white)
![Docker](https://img.shields.io/badge/Docker-Compose-2496ED?style=flat&logo=docker&logoColor=white)
![Gradle](https://img.shields.io/badge/Gradle-8.x-02303A?style=flat&logo=gradle&logoColor=white)

> 멀티에이전트 기반 금융 거래 백엔드 시뮬레이션 시스템
<img width="1128" height="573" alt="image" src="https://github.com/user-attachments/assets/38b26162-58f0-4c8b-82bb-e84964693518" />
<br>

## 📅 개발 로드맵

| 주차 | 기간 | 목표 | 상세 | 상태 |
|------|------|------|------|------|
| 1주 | 5/11~5/17 | 프로젝트 뼈대 | 헥사고날 구조, DB 스키마, Docker Compose | ✅ 완료 |
| 2주 | 5/18~5/24 | 계좌 도메인 | Account 엔티티, 복식부기 원장(DEBIT/CREDIT) | ✅ 완료 |
| 3주 | 5/25~5/31 | 송금 코어 | TransferUseCase, 비관적 락, 데드락 방지(ID 오름차순) | ✅ 완료 |
| 4주 | 6/1~6/14 | 에이전트 엔진 | SimulationEngine(@Scheduled), 에이전트 10명, chancePay 기반 자율 송금 | ✅ 완료 |
| 5주 | 6/15~6/28 | 실시간 대시보드 | SSE 피드, SVG 거래흐름 그래프, 예측 패널, 시뮬레이션 제어 | ✅ 완료 |
| 6주 | 6/29~7/5 | 소비 UseCase | SpendUseCase — 에이전트 → 외부 가맹점 결제 (chanceSpend 기반) | ✅ 완료 |
| 7주 | 7/6~7/12 | 투자 UseCase | InvestUseCase — 수익률 정규분포 N(μ,σ²), 음수 수익률 포함 (chanceInvest 기반) | ✅ 완료 |
| 8주 | 7/13~7/19 | 환불 UseCase | RefundUseCase — 소비 트랜잭션 역거래, 원장 보정 (chanceRefund 기반) | ✅ 완료 |
| 9주 | 7/20~7/26 | Redis 캐시 | 잔액 캐싱(버전 비교), Redis 분산 락(SET NX PX + Lua 해제) | ✅ 완료 |
| 10주 | 7/27~8/2 | Kafka 이벤트 | 커밋 후(AFTER_COMMIT) 거래 이벤트 발행, 외부 정산 시스템 Consumer | ✅ 완료 |
| 11주 | 8/3~8/9 | 부하 테스트 | 동시 N건 송금 → Lost Update 0건 증명, ∑DEBIT=∑CREDIT 검증 | ✅ 완료 |
| 12주 | 8/10~8/16 | 마무리 | 멱등성 키, 관측성(Prometheus/Grafana), k6 부하 테스트 | ✅ 완료 |
| 2차 | 11/6~11/15 | 신뢰성 고도화 | Transactional Outbox, 멱등 소비자, 일마감 대사, 락 전략 실험, ArchUnit, CI | ✅ 완료 |

<br>

## 💡 프로젝트 배경

대부분의 학부 프로젝트는 **단순 CRUD**에 머물러 있습니다.

실제 금융 서비스에서 가장 중요한 것은 기능 구현 자체가 아닙니다.  
수많은 요청이 동시에 몰렸을 때도 **단 1원의 오차 없이** 처리해내는 시스템의 신뢰성입니다.

이 프로젝트는 그 질문에서 출발했습니다.

> **"학부생이 만든 시스템이 실제 금융 트래픽을 버텨낼 수 있는가?"**

토스뱅크의 코어뱅킹 MSA 전환 사례를 분석하고, 실무에서 사용하는 설계 원칙을  
직접 구현하고 검증하는 것이 이 프로젝트의 출발점입니다.

<br>

## 🎯 무엇을 증명하는가

이 프로젝트는 단순히 "돌아가는 시스템"을 만드는 것이 목표가 아닙니다.  
**극한 상황에서도 데이터 정합성을 유지하는 시스템**을 설계하고 코드로 증명합니다.

| 검증 시나리오 | 테스트 방법 | 기대 결과 |
|-------------|-----------|---------|
| 단일 계좌 동시 송금 | 멀티스레드로 동시 100건 요청 | Lost Update 없이 잔액 정합성 100% 보장 |
| 양방향 동시 송금 | A→B, B→A 정확히 동시 실행 | 데드락 예외 없이 모든 요청 정상 처리 |
| 잔액 초과 검증 | 현재 잔액보다 큰 금액 동시 다발 송금 | 조건 위반 시 즉시 예외 및 DB 전체 롤백 |
| 원장 무결성 검사 | 장기 시뮬레이션 후 원장 합산 비교 | ∑ DEBIT = ∑ CREDIT (수학적 무결성 증명) |
| 이벤트 유실 | 브로커 장애 / ACK 유실을 주입한 채 송금 | 커밋된 거래 수 = 외부 정산 장부 행 수 (유실 0, 중복 0) |
| 장부 대사 | 정산 장부에 누락·변조·불명 거래를 일부러 만듦 | 일마감 대사가 거래 id 단위로 정확히 탐지 |
| 락 전략 선택 | 비관적·낙관적·조건부 UPDATE 를 4개 시나리오로 실측 | 핫스팟에서 비관적 락 4배 처리량, 낙관적 락 Lost Update 발견 |

<br>

## 🆚 기존 학부 프로젝트와의 차별성

| | 일반적인 학부 프로젝트 | AI Finance World |
|--|---------------------|-----------------|
| 핵심 관심사 | 기능 구현 (CRUD) | 트랜잭션 제어 · 원장 설계 |
| AI의 역할 | 화려한 메인 기능 | 부하를 발생시키는 테스트 도구 |
| 동시성 | 1인 사용자 가정 | 다수 에이전트 동시 접근 설계 |
| 데이터 설계 | 잔액 필드 직접 수정 | 복식부기 원장 기반 잔액 재계산 |
| 아키텍처 | 단일 계층 강결합 | 헥사고날 아키텍처 (도메인 분리) |
| 검증 방식 | 눈으로 확인 | 부하 테스트 · 수학적 무결성 증명 |

<br>

## 📌 프로젝트 소개

AI 에이전트 10명이 가상 금융 생태계에서 **송금 · 소비 · 투자 · 환불** 등  
실제 경제 활동을 수행하고, 그 모든 거래를 은행 백엔드가 안정적으로 처리합니다.  
모든 거래는 **SSE(Server-Sent Events)** 로 실시간 대시보드에 push됩니다.

```
에이전트 10명 ── 송금/소비/투자/환불 ──▶  은행 백엔드  ──▶  실시간 대시보드
                                        (핵심 시스템)       SSE push / SVG 그래프
                                        분산 락 + 비관적 락  잔액 예측 패널
                                        복식부기 원장        시뮬레이션 제어
                                             │
                                             ├─ 같은 트랜잭션 ──▶ Outbox ──릴레이──▶ Kafka ──▶ 외부 정산 시스템(멱등)
                                             ├─ 커밋 후 ──▶ Redis 잔액 캐시                         │
                                             └─ 매일 00:10 일마감 대사 ◀──────────────────────────┘
```

> "이 프로젝트의 주인공은 AI가 아니라, **백엔드 시스템**입니다."

<br>

## 🏗 아키텍처

헥사고날 아키텍처(Ports & Adapters) 기반으로 설계했습니다.  
비즈니스 로직이 외부 기술(DB, Kafka, Redis)에 종속되지 않습니다.

```
com.aifinance
├── domain                       # 비즈니스 규칙
│   ├── account                  # 계좌 (AGENT / MERCHANT / MARKET / CAPITAL), 잔액 부족 규칙
│   ├── ledger                   # 원장 엔트리 (복식부기, transfer_id 로 거래와 연결)
│   ├── transfer                 # 거래 이력 + 거래유형(송금/소비/투자/환불/급여/개설)
│   ├── investment               # 투자 포지션 (만기, 수익률, 정산)
│   ├── outbox                   # 발행 대기 이벤트 (PENDING / PUBLISHED / DEAD, 백오프)
│   ├── reconciliation           # 일마감 대사 결과·차이 내역
│   └── agent                    # AI 에이전트 페르소나
├── application
│   ├── port/out                 # 외부 기술 추상화 (Account/Ledger/Transfer/Investment,
│   │                            #   DistributedLock, BalanceCache, TransactionEvent)
│   ├── service
│   │   ├── LedgerPostingService # 모든 거래의 단일 기장 지점 (ID 오름차순 비관적 락 + 분개 2건)
│   │   ├── AccountLockExecutor  # 분산 락 → 트랜잭션 → 락 해제 템플릿
│   │   ├── OutboxRelayService   # Outbox → 브로커 동기 발행 (순서 보장, 재시도, DEAD 격리)
│   │   └── TransactionPostedListener # AFTER_COMMIT → 잔액 캐시 갱신
│   └── usecase                  # Transfer / Spend / Invest / Refund / Agent(급여)
│                                #   Integrity(무결성 검증) / Reconciliation(대사) / SimulationSetup
└── adapter
    ├── in
    │   ├── scheduler            # SimulationEngine (@Scheduled 틱 엔진)
    │   ├── init                 # DataInitializer
    │   └── web                  # REST API + SSE 엔드포인트
    └── out
        ├── persistence          # JPA 구현체
        ├── messaging            # Kafka Producer / 인프로세스 브로커 / 장애 주입기 / 정산 Consumer
        ├── settlement           # 대사용 정산 장부 조회 (SQL anti-join)
        └── cache                # Redis 분산 락 · 잔액 캐시 / NoOp 대체 구현
com.aifinance.external.settlement  # 외부 정산 시스템 시뮬레이션 (멱등 수신함) — 은행 코어를 모른다
```

<br>

## 🛡 이벤트 유실 방지 — Transactional Outbox ([ADR-0002](docs/adr/0002-transactional-outbox.md))

커밋 직후 Kafka 로 보내던 구조는 "DB 커밋 ✅ → (장애) → 발행 ❌" 사이에서 이벤트가 **조용히 사라질 수 있었다.**

```
트랜잭션 { 잔액 변경 → 거래·분개 기록 → outbox_events INSERT }   ← 커밋되면 함께 남고, 롤백되면 함께 사라짐
OutboxRelay (100ms) : PENDING 을 id 순으로 동기 발행 → ack 받으면 PUBLISHED, 실패 시 지수 백오프·순서 보장, 10회 실패 시 DEAD 격리
외부 정산 시스템    : 거래 id 를 PK 로 기록 → 같은 이벤트가 몇 번 와도 1행 (at-least-once 발행 → effectively-once 효과)
```

| 검증 (`OutboxReliabilityTest`, H2 + MySQL) | 결과 |
|---|---|
| 브로커 장애 중 송금 30건 → 복구 | 송금은 계속 성공, 복구 후 전량 전달, 커밋 거래 수 = 정산 장부 행 수 |
| ACK 유실 3회 → 재발행 | 중복 도착 3건을 소비자가 제거, 장부는 거래당 1행 |
| 잔액 부족으로 롤백된 거래 | Outbox 에도 없음 (외부로 새지 않음) |
| **실제 Kafka 컨테이너 일시정지**(`docker pause`) 중 송금 → 재가동 | 거래 성공, 대사 `PENDING_IN_FLIGHT` → 재가동 후 전량 도착, `MATCHED` (MySQL+Kafka+Redis 컨테이너) |
| 처리할 수 없는 독(poison) 메시지 | 건너뛰지 않고 DLT 에 원인과 함께 보존, 뒤 메시지는 계속 처리 |

- 발행 완료 이벤트는 7일 보관 후 1,000건 단위로 정리(`OutboxCleaner`), 대기·DEAD 이벤트는 지우지 않는다.
- 대시보드 상단 **신뢰성 컨트롤 룸**에서 Outbox 대기 건수, 장애 주입 버튼, 대사 결과를 실시간으로 볼 수 있다.

```bash
curl -X POST localhost:8080/chaos/broker/down   # 장애 주입 (local 프로필)
curl localhost:8080/ops/outbox                  # pending 증가 — 거래는 계속 성공
curl -X POST localhost:8080/chaos/broker/up     # 복구 → pending 0 으로 수렴
```

<br>

## 📒 일마감 대사 — Reconciliation ([ADR-0003](docs/adr/0003-end-of-day-reconciliation.md))

매일 00:10 전날 영업일의 **은행 원장 ↔ 외부 정산 장부**를 맞춰 본다. 두 장부를 메모리에 올리지 않고 SQL anti-join 으로 차이만 뽑는다.

| 분류 | 의미 |
|---|---|
| `MISSING_IN_SETTLEMENT` | 은행에만 있음 (진짜 유실) |
| `IN_FLIGHT` | Outbox 에 대기 중 — 차이로 보지 않음 (오탐 방지) |
| `UNEXPECTED_IN_SETTLEMENT` | 정산 장부에만 있음 |
| `AMOUNT_MISMATCH` | 금액 불일치 |

결과(`MATCHED` / `PENDING_IN_FLIGHT` / `UNMATCHED`)와 차이 내역, 같은 시점의 원장 무결성 검증 결과를 이력으로 남긴다.

<br>

## ⚖️ 락 전략 비교 실험 ([보고서](docs/experiments/lock-strategy-benchmark.md) · [ADR-0004](docs/adr/0004-pessimistic-locking.md))

같은 원장 기록을 두고 잔액 갱신 방식만 바꿔 MySQL 8.0 에서 실측했다 (32 스레드, 실행당 2,000건, CI 에서 매번 재실행).

| 핫스팟(1개 계좌로 집중) | 비관적 락 | 낙관적 락 | 조건부 UPDATE |
|---|---:|---:|---:|
| TPS | 731 | 187 | 844 |
| p99 | 50ms | 822ms | 76ms |
| 실패 | 0% | 5.3% | 0% |
| 무결성 | ✅ | ❌ Lost Update | ✅ |

- **발견**: MySQL REPEATABLE READ 에서 일반 SELECT 로 읽은 잔액을 절대값으로 덮어쓰는 낙관적 락이 약 1만 커밋당 1건 돈을 잃었다.
  감사 로그로 "최신 버전 + 직전 잔액"을 읽은 지점을 찾아냈고, 같은 코드를 READ COMMITTED 로 돌리면 22,549 커밋 중 0건,
  상대값(`balance = balance ± ?`) 갱신은 낡은 값을 읽어도 돈을 잃지 않았다.
- **핫스팟 완화**: 같은 비관적 락에서 수신 계좌를 8개 서브계좌로 나누면 TPS **2.3배**.

<br>

## 🛠 기술 스택

| 분류 | 기술 | 선택 이유 |
|------|------|---------|
| Language | Java 17 | 금융권 표준 언어 |
| Framework | Spring Boot 4.0 | 금융권 백엔드 표준 스택 |
| ORM | Spring Data JPA | `@Lock`으로 비관적 락 적용 |
| Database | MySQL 8.0 | 토스뱅크 채널계 스택 기준 |
| Cache / Lock | Redis | 분산 락(다중 인스턴스 대비), 잔액 캐시 — `infra` 프로필에서 활성화 |
| Message Queue | Apache Kafka | 커밋된 거래 이벤트 비동기 발행 — `infra` 프로필에서 활성화 |
| 실시간 통신 | SSE (Server-Sent Events) | 폴링 없는 실시간 거래 피드 |
| Infra | Docker Compose | 로컬 환경 일관성 |

<br>

## 🔑 핵심 설계 원칙

**복식부기 원장 (Double-Entry Ledger) — 닫힌 시스템**
```
모든 거래(송금/소비/투자/환불/급여/개설) = Transfer 1건 + LedgerEntry 2건(같은 transfer_id) 원자적 생성
보내는 계좌 DEBIT + 받는 계좌 CREDIT

은행 밖으로 나가는 돈도 상대 계좌를 가진다 (시스템 계좌)
  소비   : 에이전트 → 가맹점(쿠팡, 무신사 ...)
  투자   : 에이전트 → 투자시장(한국증권) → 만기 시 원금×(1+r) 정산, r ~ N(μ, σ²)
  환불   : 가맹점 → 에이전트 (원거래는 그대로 두고 역거래 추가, 중복 환불 차단)
  급여/초기자본 : 발행계정 → 에이전트

무결성 검증 4가지 (GET /integrity)
  ① ∑ DEBIT = ∑ CREDIT
  ② 모든 거래는 분개를 정확히 2건 가진다
  ③ 모든 계좌: 잔액 필드 = ∑ CREDIT - ∑ DEBIT   ← Lost Update 가 있으면 여기서 깨진다
  ④ 모든 계좌(시스템 포함) 잔액 합 = 0          ← 돈이 새거나 생기지 않음
```
> 송금만 있을 때는 ①이 구조상 항상 참이라 검증력이 약했습니다.
> 시스템 계좌를 도입하고 ③·④를 추가해, 잔액 필드와 원장이 실제로 일치하는지까지 증명합니다.

**동시성 제어 (2단 락)**
```
[Redis 분산 락: 계좌 ID 오름차순]          ← 서버 여러 대일 때 (infra 프로필)
   └─ [DB 트랜잭션 시작]
         └─ [비관적 락 SELECT ... FOR UPDATE: 계좌 ID 오름차순]
               잔액 검증 → 잔액 변경 → Transfer + DEBIT/CREDIT 기록
      [커밋]
   └─ AFTER_COMMIT: Redis 잔액 캐시 갱신, Kafka 이벤트 발행
[분산 락 해제]   ← 반드시 커밋 이후에 해제
```
```java
// LedgerPostingService — 모든 거래가 이 한 곳을 지난다
// 계좌 ID 오름차순 락 획득 → A→B, B→A 동시 요청에도 데드락 없음
if (fromAccountId < toAccountId) {
    fromAccount = lock(fromAccountId);
    toAccount   = lock(toAccountId);
} else {
    toAccount   = lock(toAccountId);
    fromAccount = lock(fromAccountId);
}
fromAccount.withdraw(amount);   // 에이전트 계좌는 잔액 초과 시 예외 → 전체 롤백
toAccount.deposit(amount);
```
- **환불 이중 처리 방지**: 원거래 행을 `PESSIMISTIC_WRITE` 로 잠근 뒤 `refunded` 플래그 확인
- **투자 이중 정산 방지**: 투자 행을 잠근 뒤 상태(OPEN/SETTLED) 확인
- **캐시 역전 방지**: Redis 에 `version(거래 id):잔액` 저장, Lua 스크립트로 더 최신 version 일 때만 덮어씀
- **롤백된 거래는 외부로 안 나감**: `@TransactionalEventListener(AFTER_COMMIT)`

**SSE 실시간 피드**
```
거래 발생 → SimulationEngine → FeedSseService.publish(json)
                                      ↓
                            연결된 모든 브라우저에 즉시 push
                            (EventSource('/feed/stream'))
```

<br>

## 👤 에이전트 페르소나

> 각 확률은 틱당 해당 행동 발생 여부를 결정하는 베르누이 분포 파라미터입니다.

| 에이전트 | 초기 잔액 | 특성 | 소비(chanceSpend) | 송금(chancePay) | 투자(chanceInvest) | 환불(chanceRefund) |
|---------|---------|------|:-:|:-:|:-:|:-:|
| 🚀 일론머스크 | 2,900,000원 | 공격적 투자자 | 80% | 40% | 10% | 20% |
| 📱 이재용 | 1,200,000원 | 신중한 관리자 | 30% | 20% | 20% | 10% |
| 💰 워런버핏 | 4,200,000원 | 장기 가치 투자자 | 40% | 20% | **90%** | 10% |
| 💬 김범수 | 3,500,000원 | 플랫폼 확장가 | 40% | **80%** | 10% | 10% |
| ⚽ 손흥민 | 1,600,000원 | 팀 플레이어 | 75% | 30% | 5% | 30% |
| 🎤 유재석 | 4,000,000원 | 국민 MC | 60% | 30% | 20% | 10% |
| 🎵 아이유 | 2,700,000원 | 감성 소비자 | 70% | 20% | 10% | 20% |
| ⚾ 박찬호 | 3,100,000원 | 투지형 | 30% | 20% | 40% | 10% |
| 🎬 김태희 | 2,600,000원 | 충동 소비자 | **95%** | 20% | 0% | **50%** |
| ⚔️ 이순신 | 3,300,000원 | 수호자 | 40% | 30% | 20% | 5% |

<br>

## 🖥 대시보드 기능

| 기능 | 설명 |
|------|------|
| SVG 거래흐름 그래프 | 에이전트 노드 크기 = 잔액 비례, 거래 시 애니메이션 |
| 실시간 거래 피드 | SSE push — 새 거래가 위에서 쌓이는 채팅창 방식 |
| 터미널 로그 | 거래 발생 시 백엔드 로그처럼 출력 |
| 통계 패널 | 총 거래건수, 총 통화량, 복식부기 무결성(balanced) |
| 잔액 예측 패널 | 선형회귀로 에이전트별 상승/하락 트렌드 예측 |
| 에이전트 팝업 | 클릭 시 잔액·성격·지출확률 정보 표시 |
| 속도 슬라이더 | 1ms ~ 3000ms/틱 실시간 조절 (느림/보통/빠름/초고속) |
| 시뮬레이션 제어 | 시작 / 일시정지 / 정지 / 초기화 |
| 거래 유형 표시 | 피드에 송금 · 소비 · 투자 · 투자정산 · 환불 · 급여 표시 |
| 외부 시스템 애니메이션 | 소비/투자/환불 시 에이전트 ↔ 은행 ↔ 가맹점·증권사 박스로 이동 |

<br>

## 🌐 API

| Method | Endpoint | 설명 |
|--------|----------|------|
| GET | `/` | 대시보드 메인 페이지 |
| GET | `/feed/stream` | SSE 실시간 거래 피드 |
| GET | `/actuator/prometheus` | 거래 지표 (Prometheus 수집용) |
| GET | `/feed` | 최근 거래 20건 |
| GET | `/stats` | 통계 + 복식부기 무결성 요약 + 유형별 거래 건수 |
| GET | `/integrity` | 무결성 검증 상세 (4가지 조건, 불일치 계좌 목록) |
| GET | `/agents` | 에이전트 목록 + 잔액 |
| GET | `/accounts` | 전체 계좌 (시스템 계좌 포함) |
| GET | `/accounts/{id}/balance` | 잔액 조회 (Redis 캐시 우선, 없으면 DB) |
| GET | `/transfers` | 전체 거래 이력 |
| POST | `/transfers?from=&to=&amount=` | 송금 (`Idempotency-Key` 헤더 지원) |
| POST | `/spends?from=&merchant=&amount=` | 소비 (가맹점 결제, `Idempotency-Key` 헤더 지원) |
| POST | `/refunds/{spendTransferId}` | 환불 |
| POST | `/investments?from=&amount=&holdTicks=` | 투자 납입 |
| POST | `/investments/{id}/settle?rate=` | 투자 정산 (rate 생략 시 정규분포 샘플링) |
| POST | `/simulation/start` | 시뮬레이션 시작 |
| POST | `/simulation/pause` | 일시정지 |
| POST | `/simulation/stop` | 정지 |
| POST | `/simulation/reset` | 초기화 (거래 삭제 + 잔액 복원) |
| POST | `/simulation/speed?ms={n}` | 틱 속도 설정 (1 ~ 3000ms) |
| GET | `/ops/outbox` | Outbox 상태 (PENDING / PUBLISHED / DEAD, 가장 오래된 대기 시간) |
| POST | `/ops/outbox/relay` | 릴레이 즉시 1회 실행 |
| POST | `/ops/outbox/requeue-dead` | DEAD 이벤트 재발행 대기열로 |
| POST | `/ops/reconciliation?date=` | 대사 실행 (date 생략 시 오늘 0시~지금) |
| GET | `/ops/reconciliation[/latest]` | 대사 이력 / 최근 결과 상세 |
| POST | `/chaos/broker/down` · `/up` · `/ack-loss?n=` | 장애 주입 (`app.chaos.enabled=true` 일 때만) |

<br>

## ⚙️ 실행 방법

### 0. 한 번에 전부 (시연용)
```bash
docker compose --profile app up -d --build   # 앱 + MySQL + Redis + Kafka + Prometheus + Grafana
```
- 대시보드 http://localhost:8080 · Grafana http://localhost:3000 · 장애 주입 버튼 활성화 상태
- CI 의 `E2E` 잡이 같은 명령으로 전체 스택을 띄워 k6 부하를 건 뒤 무결성·Outbox 전량 발행·대사 일치를 확인한다

### 1. 인프라만 실행 (개발용)
```bash
docker compose up -d          # MySQL + Redis + Kafka (+ Prometheus, Grafana)
```

### 2. 애플리케이션 실행
```bash
./gradlew bootRun --args='--spring.profiles.active=local' # DB 설치 없이 H2 메모리 DB로 실행 (시연용)
./gradlew bootRun                                         # MySQL 만 사용 (Redis/Kafka OFF)
./gradlew bootRun --args='--spring.profiles.active=infra' # Redis 분산 락·캐시 + Kafka 이벤트 ON
```

### 3. 대시보드 접속
```
http://localhost:8080
```

### 4. 테스트
```bash
./gradlew test            # H2 인메모리 DB (MySQL/Redis/Kafka 불필요)
./gradlew mysqlTest       # Testcontainers MySQL 8 에서 같은 테스트 (Docker 필요)
./gradlew mysqlBenchmark  # 락 전략 비교 실험 (Docker 필요) → build/benchmark/
```
모든 push 마다 GitHub Actions 가 세 가지를 모두 실행한다.

| 테스트 | 검증 내용 |
|--------|----------|
| `ConcurrentTransferTest` | 단일방향 100건 / 양방향 50+50건 동시 송금 × 각 10회 — 성공 건수, 최종 잔액, 원장 건수·합계, 송금 이력 |
| `ConcurrentRefundTest` | 같은 소비 건에 환불 20건 동시 요청 → 정확히 1건만 성공 |
| `LedgerScenarioTest` | 소비 / 환불 / 중복환불 거절 / 투자 수익·손실·전액손실 / 급여 / 잔액 초과 롤백 |
| `SimulationIntegrityTest` | 300틱 시뮬레이션 후 무결성 4가지 조건 모두 성립 |
| `IdempotencyTest` | 같은 Idempotency-Key 50건 동시 재전송 → 송금 1건, 키 재사용·내용 불일치 거절 |
| `OutboxReliabilityTest` | 브로커 장애·ACK 유실·롤백·동시 200건·DEAD 격리에서 유실 0 / 중복 0, 보관 기간 정리 |
| `FullInfraOutboxIntegrationTest` | MySQL + **실제 Kafka + Redis** 컨테이너: Kafka 일시정지·재가동, 독 메시지 DLT, Redis 캐시 일치 |
| `MySqlIndexUsageTest` | 릴레이 폴링·대사·환불 조회가 의도한 인덱스를 쓸 수 있는지 `EXPLAIN` 확인 |
| `ReconciliationTest` | MATCHED / 전달 중 오탐 방지 / 누락·불명·금액불일치 정확 탐지 / 이력 |
| `ArchitectureTest` | 헥사고날 의존 방향·외부 시스템 분리·순환 의존 금지 (ArchUnit) |
| `LockStrategyBenchmark` | 락 전략 4종 × 시나리오 4종 처리량·지연·정합성 (`benchmark` 태그, 별도 실행) |

<br>

## 📁 참고 자료

- [토스뱅크 은행 최초 코어뱅킹 MSA 전환기 — SLASH23](https://toss.tech/article/slash23-corebanking)
- 인천대학교 정보통신공학과 분포학습연구실

<br>

---

> 개발 기간: 2026.05 ~ 2026.11  
> 개발자: 김재웅 (인천대학교 정보통신공학과)
