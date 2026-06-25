# AI Finance World


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
| 6주 | 6/29~7/5 | 소비 UseCase | SpendUseCase — 에이전트 → 외부 가맹점 결제 (chanceSpend 기반) | 🔨 진행중 |
| 7주 | 7/6~7/12 | 투자 UseCase | InvestUseCase — 수익률 정규분포 N(μ,σ²), 음수 수익률 포함 (chanceInvest 기반) | ⬜ |
| 8주 | 7/13~7/19 | 환불 UseCase | RefundUseCase — 소비 트랜잭션 역거래, 원장 보정 (chanceRefund 기반) | ⬜ |
| 9주 | 7/20~7/26 | Redis 캐시 | 잔액 캐싱, Redisson 분산 락 | ⬜ |
| 10주 | 7/27~8/2 | Kafka 이벤트 | 거래 이벤트 비동기 발행, 외부 시스템 연동 시뮬레이션 | ⬜ |
| 11주 | 8/3~8/9 | 부하 테스트 | 동시 N건 송금 → Lost Update 0건 증명, ∑DEBIT=∑CREDIT 검증 | ⬜ |
| 12주 | 8/10~8/16 | 마무리 | 발표 준비, 문서화 | ⬜ |

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

AI 에이전트 10명이 가상 금융 생태계에서 **송금** 등  
실제 경제 활동을 수행하고, 그 모든 거래를 은행 백엔드가 안정적으로 처리합니다.  
모든 거래는 **SSE(Server-Sent Events)** 로 실시간 대시보드에 push됩니다.

```
에이전트 10명 ── 자율 송금 ──▶  은행 백엔드  ──▶  실시간 대시보드
                              (핵심 시스템)       SSE push / SVG 그래프
                              비관적 락           잔액 예측 패널
                              복식부기 원장        시뮬레이션 제어
```

> "이 프로젝트의 주인공은 AI가 아니라, **백엔드 시스템**입니다."

<br>

## 🏗 아키텍처

헥사고날 아키텍처(Ports & Adapters) 기반으로 설계했습니다.  
비즈니스 로직이 외부 기술(DB, Kafka, Redis)에 종속되지 않습니다.

```
com.aifinance
├── domain                  # 순수 비즈니스 로직 (외부 기술 0)
│   ├── account             # 계좌 엔티티 + Repository 인터페이스
│   ├── ledger              # 원장 엔트리 (복식부기)
│   ├── transfer            # 송금 도메인
│   └── agent               # AI 에이전트 페르소나
├── application             # 흐름 조율 (UseCase)
│   └── usecase
│       └── TransferUseCase # 핵심 송금 로직 (비관적 락 + 복식부기)
└── adapter                 # 외부 기술 연결
    ├── in
    │   ├── scheduler       # SimulationEngine (@Scheduled 틱 엔진)
    │   └── web             # REST API + SSE 엔드포인트
    └── out
        ├── persistence     # JPA 구현체
        ├── messaging       # Kafka Producer
        └── cache           # Redis
```

<br>

## 🛠 기술 스택

| 분류 | 기술 | 선택 이유 |
|------|------|---------|
| Language | Java 17 | 금융권 표준 언어 |
| Framework | Spring Boot 4.0 | 금융권 백엔드 표준 스택 |
| ORM | Spring Data JPA | `@Lock`으로 비관적 락 적용 |
| Database | MySQL 8.0 | 토스뱅크 채널계 스택 기준 |
| Cache | Redis | 잔액 캐싱 (예정) |
| Message Queue | Apache Kafka | 비동기 이벤트 분리 (예정) |
| 실시간 통신 | SSE (Server-Sent Events) | 폴링 없는 실시간 거래 피드 |
| Infra | Docker Compose | 로컬 환경 일관성 |

<br>

## 🔑 핵심 설계 원칙

**복식부기 원장 (Double-Entry Ledger)**
```
하나의 송금 이벤트 = LedgerEntry 2건 원자적 생성
송금자 DEBIT + 수신자 CREDIT
잔액 = ∑ CREDIT - ∑ DEBIT
∑ DEBIT = ∑ CREDIT → 수학적 무결성 보장
```

**동시성 제어**
```java
// 비관적 락으로 동시 접근 차단
@Lock(LockModeType.PESSIMISTIC_WRITE)

// 계좌 ID 오름차순 락 획득 → 데드락 방지
// A→B, B→A 동시 요청에도 데드락 없음
if (fromAccountId < toAccountId) {
    first  = accountPort.findByIdWithLock(fromAccountId);
    second = accountPort.findByIdWithLock(toAccountId);
} else {
    first  = accountPort.findByIdWithLock(toAccountId);
    second = accountPort.findByIdWithLock(fromAccountId);
}
```

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

<br>

## 🌐 API

| Method | Endpoint | 설명 |
|--------|----------|------|
| GET | `/` | 대시보드 메인 페이지 |
| GET | `/feed/stream` | SSE 실시간 거래 피드 |
| GET | `/feed` | 최근 거래 20건 |
| GET | `/stats` | 통계 + 복식부기 무결성 검증 |
| GET | `/agents` | 에이전트 목록 + 잔액 |
| POST | `/simulation/start` | 시뮬레이션 시작 |
| POST | `/simulation/pause` | 일시정지 |
| POST | `/simulation/stop` | 정지 |
| POST | `/simulation/reset` | 초기화 (거래 삭제 + 잔액 복원) |
| POST | `/simulation/speed?ms={n}` | 틱 속도 설정 (1 ~ 3000ms) |

<br>

## ⚙️ 실행 방법

### 1. DB 환경 실행
```bash
docker-compose up -d
```

### 2. 애플리케이션 실행
```bash
./gradlew bootRun
```

### 3. 대시보드 접속
```
http://localhost:8080
```

<br>

## 📁 참고 자료

- [토스뱅크 은행 최초 코어뱅킹 MSA 전환기 — SLASH23](https://toss.tech/article/slash23-corebanking)
- 인천대학교 정보통신공학과 분포학습연구실

<br>

---

> 개발 기간: 2026.05 ~ 2026.07  
> 개발자: 김재웅 (인천대학교 정보통신공학과)
