# 앱 2대 동시 실행 실험

같은 DB(MySQL)·Redis·Kafka 를 보는 앱 인스턴스 2대(8080, 8081)에 HTTP 부하를 나눠 보냈을 때 무엇이 안전하고 무엇이 안전하지 않은지 확인한다.
**수치는 실제로 돌린 값만 적고, 돌리지 못한 값은 "미실행"으로 적는다. 문제가 발견되면 그대로 기록한다(그 자체가 결과다).**

## 구성

- `docker-compose.yml`: `app`(8080) + `app2`(8081, `--profile app2`). 같은 MySQL/Redis/Kafka.
- 엔진은 양쪽 모두 정지(`APP_SIMULATION_AUTO_START=false` + `/loadtest/setup` 의 `stop()`)해 가상 시계를 고정하고, 부하는 k6 와 같은 HTTP 송금으로만 준다.
  **가상 자정 대사는 이 시나리오의 범위가 아니다** (아래 "코드에서 확인한 구조적 위험" 3번 참고).
- 두 변형: `redis-on`(릴레이 분산 락 있음), `redis-off`(락이 no-op).
- 도구: `tools/experiments/multi_instance.py`, workflow `multi-instance.yml` (수동 실행, 결과는 `experiment-results` 브랜치).

## 코드에서 확인한 구조적 위험 (실험 전에 읽어서 알아낸 것)

실측이 아니라 코드 읽기로 확인한 사실이다.

1. **릴레이 락은 Redis 가 꺼지면 항상 성공한다.** `OutboxRelayService.relayOnce()` 는 `DistributedLockPort.tryLock("lock:outbox-relay", 대기 0, 임대 30초)` 를 쓴다.
   `app.redis.enabled=false`(기본값)이면 `NoOpCacheConfig` 가 항상 "획득 성공"을 돌려주므로, **앱 2대의 릴레이가 동시에 돌아 같은 PENDING 이벤트를 둘 다 읽을 수 있다.**
   `OutboxRepository.findPublishable` 에는 `FOR UPDATE SKIP LOCKED` 도 없다. 즉 단일 발행을 보장하는 장치는 Redis 락 하나뿐이다.
2. **중복 발행은 멱등 소비자가 흡수한다.** 위 1번으로 같은 이벤트가 두 번 나가도 `SettlementInbox` 가 transfer id 로 걸러 정산 장부에는 한 줄만 남는다
   (`delivery_count` 가 흡수한 횟수). 그래서 방어선이 두 겹이다: ① 릴레이 락(중복 발행 자체를 줄임) ② 멱등 소비자(발행이 중복돼도 정산은 한 번).
   다만 같은 키(보내는 계좌)의 **순서**는 두 릴레이가 동시에 발행하면 보장되지 않는다 — 멱등 소비자는 순서를 고치지 못한다.
3. **엔진 상태·가상 시계·틱은 JVM 메모리에 있다.** 앱마다 `VirtualClock` 이 따로 있어서, 한 앱만 틱을 돌리면 다른 앱의 HTTP 거래는 `occurred_at` 이 틱 0 시각으로 찍힌다.
   그래서 이 실험은 엔진을 양쪽 다 꺼 시계를 고정했다. 엔진을 2대에서 모두 켜면 같은 에이전트를 두 앱이 각자 움직인다 — 리더 선출이 없다.
4. **스키마 생성이 위험하다.** 기본 `spring.jpa.hibernate.ddl-auto=create` 라서 2번째 앱을 기동하면 **DB 를 지우고 다시 만든다.** 그래서 `app2` 는 `ddl-auto=none` 이고 첫 앱이 healthy 해진 뒤에 기동한다.
5. **기동 시 월드 생성 경합.** `bootstrap=true` 면 두 앱이 동시에 뜰 때 `initialize()` 가 둘 다 `existing==0` 으로 보고 시스템 계좌를 중복 생성할 수 있다 (순차 기동으로 회피).
6. **Kafka 소비자.** 토픽이 파티션 1개로 자동 생성되면 같은 consumer group(`external-settlement`) 의 앱 2대 중 한 대만 소비한다. 그 앱이 죽으면 리밸런스 시간만큼 정산 수신이 멈춘다.

## 시나리오

1. **부하 분산**: 두 앱에 HTTP 부하를 나눠 보낸 뒤 Outbox 발행 건수, 정산 수신 건수, 중복 수신(멱등이 흡수한 수), 키별 순서 위반, 대사 결과를 기록.
2. **브로커 장애**: 두 앱 모두 `/chaos/broker/down` → 부하 → `up` → 대사 MATCHED 확인.
3. **인계 시간**: 릴레이 락을 잡고 있는 순간(Redis 키 `lock:outbox-relay` 가 보일 때) 그 앱을 `docker kill` → 다른 앱이 락(임대 30초)을 이어받는 데 걸린 시간.

## 실측 결과 (CI)

<!-- RESULTS:multi-instance -->
미실행 — 이 PC 에는 Docker 가 없어 로컬에서 돌리지 못했다. CI(`multi-instance.yml`) 실행 결과가 `experiment-results` 브랜치에 올라오면 채운다.
