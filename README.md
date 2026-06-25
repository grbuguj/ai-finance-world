# AI Finance World

> 인천대학교 정보통신공학과 졸업작품  
> 멀티 에이전트 금융 거래 시뮬레이션 백엔드 + 실시간 대시보드

---

## 프로젝트 개요

10명의 AI 에이전트가 각자의 성격(지출 확률)에 따라 자율적으로 송금 거래를 수행하는 금융 시뮬레이션입니다.  
모든 거래는 복식부기(DEBIT/CREDIT) 원장으로 기록되며, SSE를 통해 실시간으로 대시보드에 반영됩니다.

## 주요 기능

- **멀티 에이전트 시뮬레이션** — 에이전트별 `chancePay` 확률로 자율 거래 발생
- **실시간 대시보드** — SSE(Server-Sent Events)로 거래 피드 즉시 push
- **복식부기 원장** — 모든 거래를 DEBIT/CREDIT으로 이중 기록, 차변=대변 무결성 검증
- **비관적 락 + 데드락 방지** — 계좌 ID 오름차순 락 획득으로 동시 송금 충돌 해결
- **시뮬레이션 제어** — 시작 / 일시정지 / 정지 / 초기화, 속도 슬라이더(1ms ~ 3000ms/틱)
- **SVG 거래흐름 그래프** — 에이전트 노드 크기가 잔액 비례, 거래 발생 시 애니메이션
- **잔액 예측 패널** — 선형회귀로 에이전트별 상승/하락 트렌드 예측

## 에이전트 목록

| 에이전트 | 초기 잔액 | 특성 |
|---------|---------|------|
| 🚀 일론머스크 | 2,900,000원 | 공격적 투자자 |
| 📱 이재용 | 1,200,000원 | 신중한 관리자 |
| 💰 워런버핏 | 4,200,000원 | 장기 가치 투자자 |
| 💬 김범수 | 3,500,000원 | 플랫폼 확장가 |
| ⚽ 손흥민 | 1,600,000원 | 팀 플레이어 |
| 🎤 유재석 | 4,000,000원 | 국민 MC |
| 🎵 아이유 | 2,700,000원 | 감성 소비자 |
| ⚾ 박찬호 | 3,100,000원 | 투지형 |
| 🎬 김태희 | 2,600,000원 | 트렌드 세터 |
| ⚔️ 이순신 | 3,300,000원 | 수호자 |

## 기술 스택

| 분류 | 기술 |
|------|------|
| Backend | Spring Boot 4.0.6, Java 17 |
| Architecture | Hexagonal Architecture (Port & Adapter) |
| Database | MySQL 8.0, Spring Data JPA |
| 동시성 | Pessimistic Lock (`SELECT FOR UPDATE`) |
| 실시간 통신 | SSE (Server-Sent Events) |
| Frontend | Vanilla JS, CSS Glassmorphism, SVG |
| 인프라 | Docker Compose |

## 아키텍처

```
Inbound Adapter (Web/Scheduler)
    └── Application Layer (UseCase)
            └── Port (Interface)
                    └── Outbound Adapter (JPA/Cache/Messaging)
```

```
domain/
  account/     # 계좌 잔액 관리
  agent/       # 에이전트 정의 (chancePay, salary)
  transfer/    # 거래 내역
  ledger/      # 복식부기 원장

application/
  usecase/     # TransferUseCase (핵심 송금 로직)
  port/out/    # AccountPort, TransferPort, LedgerPort, AgentPort

adapter/in/
  scheduler/   # SimulationEngine (@Scheduled 틱 엔진)
  web/         # REST API, SSE 엔드포인트
  init/        # DataInitializer (에이전트 초기 데이터)

adapter/out/
  persistence/ # JPA 어댑터
```

## API

| Method | Endpoint | 설명 |
|--------|----------|------|
| GET | `/` | 대시보드 메인 페이지 |
| GET | `/feed/stream` | SSE 실시간 거래 피드 |
| GET | `/feed` | 최근 거래 20건 |
| GET | `/stats` | 통계 (총 거래건수, 총 잔액, 복식부기 검증) |
| GET | `/agents` | 에이전트 목록 + 잔액 |
| GET | `/simulation/status` | 시뮬레이션 상태 조회 |
| POST | `/simulation/start` | 시뮬레이션 시작 |
| POST | `/simulation/pause` | 일시정지 |
| POST | `/simulation/stop` | 정지 |
| POST | `/simulation/reset` | 초기화 (거래 전체 삭제 + 잔액 복원) |
| POST | `/simulation/speed?ms={n}` | 틱 속도 설정 (1 ~ 3000ms) |

## 실행 방법

**1. MySQL 실행**

```bash
docker-compose up -d
```

**2. 애플리케이션 실행**

```bash
./gradlew bootRun
```

**3. 대시보드 접속**

```
http://localhost:8080
```

## 핵심 구현 포인트

### 데드락 방지
두 에이전트가 동시에 서로에게 송금할 때, 항상 ID가 작은 계좌부터 락을 획득해 순환 대기를 방지합니다.

```java
if (fromAccountId < toAccountId) {
    first  = accountPort.findByIdWithLock(fromAccountId);
    second = accountPort.findByIdWithLock(toAccountId);
} else {
    first  = accountPort.findByIdWithLock(toAccountId);
    second = accountPort.findByIdWithLock(fromAccountId);
}
```

### 복식부기 무결성
모든 거래는 차변(DEBIT)과 대변(CREDIT) 두 개의 원장 항목으로 기록되며,
`/stats` 응답에 `"balanced": true/false` 로 실시간 검증 결과를 노출합니다.

### SSE 실시간 피드
거래 발생 즉시 `FeedSseService`가 연결된 모든 브라우저에 JSON을 push합니다.  
프론트엔드는 폴링 없이 `EventSource('/feed/stream')`으로 수신합니다.
