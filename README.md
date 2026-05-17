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

| 주차 | 기간 | 목표 | 상태 |
|------|------|------|------|
| 1주 | 5/11~5/17 | 프로젝트 뼈대 (헥사고날 구조, DB 스키마) | 완료 |
| 2주 | 5/18~5/24 | 계좌 도메인 (복식부기 원장) | 🔨 진행중 |
| 3주 | 5/25~5/31 | 송금 코어 (비관적 락, 데드락 방지) | ⬜ |
| 4주 | 6/1~6/7 | 동시성 검증 (부하 테스트) | ⬜ |
| 5주 | 6/8~6/14 | 에이전트 엔진 (@Scheduled, 샘플링) | ⬜ |
| 6주 | 6/15~6/21 | 시뮬레이션 가동 | ⬜ |
| 7주 | 6/22~6/28 | 부하 테스트 | ⬜ |
| 8주 | 6/29~7/5 | Redis 추가 | ⬜ |
| 9주 | 7/6~7/12 | Kafka 추가 | ⬜ |
| 10주 | 7/13~7/19 | 대시보드 | ⬜ |
| 11~12주 | 7/20~7/31 | 마무리 및 발표 준비 | ⬜ |

<br>

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

AI 에이전트 10명이 가상 금융 생태계에서 **송금·소비·투자·환불** 등  
실제 경제 활동을 수행하고, 그 모든 거래를 은행 백엔드가 안정적으로 처리합니다.

```
에이전트 10명 ── 송금/소비/투자/환불 ──▶  은행 백엔드  ──▶  외부 시스템
                                        (핵심 시스템)       쿠팡 / 배민
                                                           넷플릭스 / 증권사
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
│       ├── TransferUseCase
│       ├── SpendUseCase
│       ├── InvestUseCase
│       ├── RefundUseCase
│       └── AgentUseCase
└── adapter                 # 외부 기술 연결
    ├── in
    │   └── web             # REST API Controller
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
| Cache | Redis + Redisson | 분산 락 + 잔액 캐싱 |
| Message Queue | Apache Kafka | 비동기 이벤트 분리 |
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

// Redis Distributed Lock (Redisson)
// 분산 환경에서도 중앙화된 락 관리
```

**AI 에이전트 행동 모델**
```
이벤트 발생 여부 : 베르누이 분포 (p = 0.07, 1틱 = 1분)
행동 선택        : 카테고리 분포 (송금 / 소비 / 투자 / 환불)
금액 결정        : Log-Normal 분포 (실제 거래 금액 분포 반영)
투자 수익률      : 정규 분포 N(μ, σ²) — 음수 수익률 포함
```

<br>

## 👤 에이전트 페르소나

| 번호 | 페르소나 | 월급 | 특징 |
|------|---------|------|------|
| Agent 1 | 사회초년생 (26세) | 290만 | 배달·카페 소비 많음 |
| Agent 2 | 절약형 공기업 준비생 | 120만 | 잔액 부족 자주 발생 |
| Agent 3 | 투자형 30대 직장인 | 420만 | 투자 가중치 높음 |
| Agent 4 | 가족 생활비 송금형 | 350만 | 주기적 타인 송금 많음 |
| Agent 5 | 대학생 아르바이트생 | 160만 | 충동 소비 성향 |
| Agent 6 | 신혼부부 가장 | 400만 | 고정 지출(월세 등) 위주 |
| Agent 7 | 디지털 구독 헤비유저 | 270만 | 정기 결제 다수 보유 |
| Agent 8 | 절약+소액투자 병행 | 310만 | 안정적 자산 관리 |
| Agent 9 | 소비충동 쇼핑형 | 260만 | 환불 요청 자주 발생 |
| Agent 10 | 안정지향 공무원형 | 330만 | 잔액 변동성 낮음 |

<br>

## ⚙️ 실행 방법

### 1. 사전 준비
```bash
# Docker가 설치되어 있어야 합니다
docker --version
```

### 2. DB 환경 실행
```bash
docker-compose up -d
```

### 3. 애플리케이션 실행
```bash
./gradlew bootRun
```


## 📁 참고 자료

- [토스뱅크 은행 최초 코어뱅킹 MSA 전환기 — SLASH23](https://toss.tech/article/slash23-corebanking)
- 인천대학교 정보통신공학과 분포학습연구실

<br>

---

> 개발 기간: 2026.05 ~ 2026.07  
> 개발자: 김재웅 (인천대학교 정보통신공학과)
