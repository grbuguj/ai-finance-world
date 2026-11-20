# 시드 고정 재현 실험

- 관련 코드: `SimulationEngine.randomFor`, `InvestUseCase.settleMatured(tick, rnd)`, `AgentUseCase.findAll`(id 오름차순),
  `SeedReproducibilityTest`
- 설정: `app.simulation.seed` (비우면 무작위), 대시보드 설정 화면의 "시드" 입력, `POST /simulation/setup?agentCount=N&seed=S`

## 무엇을 고정했나
한 틱의 모든 난수(행동 발생 베르누이, 상대 선택, 금액 비율, 사유·품목 선택, 투자 만기, 투자 수익률)가
`SplittableRandom(seed × 상수 + 틱 번호)` 하나에서 정해진 순서로 나온다. 순서 의존 요소도 고정했다.

| 요소 | 고정 방법 |
|---|---|
| 에이전트 순회 | `AgentUseCase.findAll()` 이 id 오름차순으로 정렬해 반환 (급여 지급 순서도 동일) |
| 가맹점 목록 | `findByType` 이 id 오름차순 |
| 만기 투자 정산 | `findByStatusAndMaturesAtTick...OrderByIdAsc` |
| 환불 후보 | `findTop5...OrderByIdDesc` |

시드를 입력하지 않고 대시보드에서 시작하면 서버가 무작위 시드를 하나 뽑아 **기록**한다(종료 요약에 표시).
그래서 무작위로 돌린 실행도 "같은 시드로 다시"로 재현할 수 있다. 엔진을 직접 호출하는 테스트에서 시드를 주지 않으면
`ThreadLocalRandom`(완전 무작위)이다.

## 재현의 비교 기준 (한계)
비결정 요소가 있어 "행 전체가 같다"가 아니라 아래 기준으로 비교한다.

- 비교하는 것: 거래 발생 순서대로의 `(유형, 보내는 이, 받는 이, 금액, 사유, 가상 발생 시각)` 시퀀스, 계좌별 최종 잔액
- 비교하지 않는 것: 거래 id(DB 자동 증가 — reset 후에도 이어짐), `created_at`(실제 시각), Outbox 이벤트 id(UUID)
- 전제: 같은 에이전트 수(에이전트 페르소나는 번호 시드로 이미 결정적), reset 후 같은 틱 수. 엔진 틱을 사람이 속도 슬라이더로
  바꾸는 것은 결과에 영향이 없다(틱 번호로만 난수가 정해진다). 단 틱 도중 외부 HTTP 거래가 끼어들면 잔액이 달라져 이후 금액이 달라진다.

## 실측 (로컬 H2, `SeedReproducibilityTest`)

| 항목 | 값 |
|---|---|
| 에이전트 수 / 계좌 수 | 10명 / 17개(시스템 계좌 7 포함) |
| 틱 수 | 160 (가상 하루 144틱을 지나 일마감 대사 트리거까지 통과) |
| 거래 건수 (시드 42) | 2,588건 (초기 자본 OPENING 10건 포함) |
| 시드 42 두 번 (reset 후) | 거래 시퀀스 완전 동일, 최종 잔액 완전 동일 |
| 시드 42 vs 43 | 시퀀스 상이, 잔액 상이 |
| 시드 없음 두 번 (60틱) | 시퀀스 상이 |
| 시퀀스에 포함 확인 | INVEST_RETURN(수익률 샘플링), REFUND 모두 포함 |
