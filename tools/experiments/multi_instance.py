#!/usr/bin/env python3
"""
① 앱 2대 동시 실행 실험 — 같은 MySQL·Redis·Kafka 를 보는 앱 2대(8080, 8081)에 HTTP 부하를 나눠 보내고 기록한다.

  시나리오 1  부하 분산: Outbox 발행/수신 건수, 중복 수신(멱등 소비자가 흡수한 수), 키(보내는 계좌)별 순서 위반, 대사
  시나리오 2  브로커 장애(down/up, 두 앱 모두): 장애 중 대기 건수, 복구 후 대사 MATCHED
  시나리오 3  릴레이 락을 잡고 있는 순간 그 앱을 강제 종료: 다른 앱이 락 만료 후 이어받는 시간 (MODE=redis-on 에서만)

엔진은 양쪽 모두 정지(APP_SIMULATION_AUTO_START=false + /loadtest/setup)해 가상 시계를 고정하고 HTTP 부하만 쓴다.
가상 자정 대사는 이 실험의 범위가 아니다.
환경변수: MODE(redis-on|redis-off), OUT, THREADS, LOAD_S
"""
import json
import os
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request

MODE = os.environ.get("MODE", "redis-on")
OUT = os.environ.get("OUT", "results/multi-instance")
THREADS = int(os.environ.get("THREADS", "20"))
LOAD_S = float(os.environ.get("LOAD_S", "25"))
APPS = ["http://localhost:8080", "http://localhost:8081"]
MYSQL, REDIS, APP1 = "ai-finance-mysql", "ai-finance-redis", "ai-finance-app"
os.makedirs(OUT, exist_ok=True)


def http(base, method, path, timeout=30):
    req = urllib.request.Request(base + path, method=method, data=b"" if method == "POST" else None)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, r.read()
    except urllib.error.HTTPError as e:
        return e.code, e.read()
    except Exception as e:
        return "ERR:" + type(e).__name__, b""


def jget(base, path, method="GET"):
    code, body = http(base, method, path)
    return json.loads(body) if code == 200 and body else None


def sql(q):
    p = subprocess.run(["docker", "exec", MYSQL, "mysql", "-ufinance", "-pfinance1234", "ai_finance", "-N", "-B", "-e", q],
                       capture_output=True, text=True)
    return p.stdout.strip()


def n(q):
    try:
        return int(float(sql(q) or 0))
    except ValueError:
        return None


def counters():
    return {
        "transfers": n("select count(*) from transfers where tx_type <> 'OPENING'"),
        "outbox_published": n("select count(*) from outbox_events where status='PUBLISHED'"),
        "outbox_pending": n("select count(*) from outbox_events where status='PENDING'"),
        "outbox_dead": n("select count(*) from outbox_events where status='DEAD'"),
        "settlement_records": n("select count(*) from settlement_records"),
        "duplicate_deliveries": n("select coalesce(sum(delivery_count - 1), 0) from settlement_records"),
        # 같은 보내는 계좌(=메시지 키)의 이벤트가 transfer id 오름차순이 아니게 수신된 건수 (received_at 순서 기준, 동시각이면 id 순으로 정렬해 과소 집계될 수 있음)
        "order_violations_per_key": n("select count(*) from (select transfer_id, lag(transfer_id) over "
                                      "(partition by sender_account_id order by received_at, transfer_id) prev "
                                      "from settlement_records) x where prev is not null and transfer_id < prev"),
    }


def diff(a, b):
    return {k: (b[k] - a[k]) if (a[k] is not None and b[k] is not None) else None for k in a}


def load(seconds, targets, accounts):
    records, stop = [], threading.Event()

    def worker(k):
        i = k
        while not stop.is_set():
            a, b = accounts[i % len(accounts)], accounts[(i * 7 + 3) % len(accounts)]
            i += 13
            if a == b:
                continue
            code, _ = http(targets[i % len(targets)], "POST", "/transfers?from=%d&to=%d&amount=1000&description=multi" % (a, b), timeout=30)
            records.append(code)

    ths = [threading.Thread(target=worker, args=(k,), daemon=True) for k in range(THREADS)]
    for t in ths:
        t.start()
    time.sleep(seconds)
    stop.set()
    for t in ths:
        t.join(timeout=40)
    return {"requests": len(records), "ok": records.count(200), "other": len(records) - records.count(200)}


def wait_drain(base, limit=150):
    t0 = time.time()
    while time.time() - t0 < limit:
        o = jget(base, "/ops/outbox")
        if o and o["pending"] == 0:
            break
        time.sleep(1)
    # 소비자가 따라잡을 시간
    t1 = time.time()
    last = None
    while time.time() - t1 < 60:
        cur = n("select count(*) from settlement_records")
        if cur == n("select count(*) from outbox_events where status='PUBLISHED'"):
            break
        last = cur
        time.sleep(1)
    return round(time.time() - t0, 1)


def reconcile(base):
    rec = None
    for _ in range(30):
        rec = jget(base, "/ops/reconciliation", "POST")
        if rec and rec["status"] == "MATCHED":
            break
        time.sleep(2)
    if rec:
        rec.pop("breaks", None)
    return rec


result = {"mode": MODE, "apps": APPS, "threads": THREADS, "load_s": LOAD_S}
setup = jget(APPS[0], "/loadtest/setup?accounts=100&prefix=MI%d" % int(time.time()), "POST")
if not setup:
    print("loadtest/setup 실패")
    sys.exit(1)
accounts = setup["accountIds"]
# 두 번째 앱의 엔진도 정지 (프로필 loadtest 의 bootstrap=true, APP_SIMULATION_AUTO_START=false 로 PAUSED 상태)
result["engine_states"] = [(jget(a, "/simulation/status") or {}).get("state") for a in APPS]
wait_drain(APPS[0], 60)

# ── 시나리오 1: 부하 분산 ───────────────────────────────────────────────────────
base1 = counters()
res1 = load(LOAD_S, APPS, accounts)
t_drain = wait_drain(APPS[0])
after1 = counters()
result["scenario1_split_load"] = {"load": res1, "drain_s": t_drain, "delta": diff(base1, after1), "reconciliation": reconcile(APPS[0])}
print("scenario1", json.dumps(result["scenario1_split_load"], ensure_ascii=False), flush=True)

# ── 시나리오 2: 브로커 장애 (두 앱 모두) ────────────────────────────────────────
base2 = counters()
for a in APPS:
    http(a, "POST", "/chaos/broker/down")
res2 = load(LOAD_S, APPS, accounts)
mid = counters()
for a in APPS:
    http(a, "POST", "/chaos/broker/up")
t_drain2 = wait_drain(APPS[0])
after2 = counters()
result["scenario2_broker_outage"] = {"load": res2, "pending_at_end_of_outage": mid["outbox_pending"], "oldest_pending_age_ms": (jget(APPS[0], "/ops/outbox") or {}).get("oldestPendingAgeMillis"),
                                     "drain_s_after_up": t_drain2, "delta": diff(base2, after2), "reconciliation": reconcile(APPS[0])}
print("scenario2", json.dumps(result["scenario2_broker_outage"], ensure_ascii=False), flush=True)

# ── 시나리오 3: 릴레이 락 보유 앱 강제 종료 → 인계 시간 (Redis 락이 있을 때만 의미) ─────
if MODE == "redis-on":
    stop = threading.Event()
    bg = threading.Thread(target=lambda: load(LOAD_S * 3, [APPS[1]], accounts), daemon=True)
    bg.start()
    token, seen_pttl = None, None
    t_wait = time.time()
    while time.time() - t_wait < 60 and not token:
        p = subprocess.run(["docker", "exec", REDIS, "redis-cli", "get", "lock:outbox-relay"], capture_output=True, text=True)
        v = p.stdout.strip()
        if v:
            token = v
            kill0 = time.time()
            subprocess.run(["docker", "kill", APP1], capture_output=True, text=True)
            kill1 = time.time()
            q = subprocess.run(["docker", "exec", REDIS, "redis-cli", "pttl", "lock:outbox-relay"], capture_output=True, text=True)
            seen_pttl = q.stdout.strip()
        else:
            time.sleep(0.02)
    s3 = {"lock_holder_token_seen": bool(token), "pttl_ms_right_after_kill": seen_pttl}
    if token:
        takeover = None
        pub0 = (jget(APPS[1], "/ops/outbox") or {}).get("published")
        deadline = time.time() + 90
        while time.time() < deadline:
            p = subprocess.run(["docker", "exec", REDIS, "redis-cli", "get", "lock:outbox-relay"], capture_output=True, text=True)
            v = p.stdout.strip()
            # 죽은 앱의 토큰이 사라졌거나(만료) 다른 토큰이 보이면 다른 인스턴스가 이어받은 것
            if v and v != token:
                takeover = time.time() - kill0
                break
            if not v:
                # 키가 비었다 = TTL 만료. 다음 relay 주기(100ms)에 이어받는다
                gone = time.time() - kill0
                time.sleep(0.3)
                takeover = time.time() - kill0
                s3["lock_expired_after_s"] = round(gone, 2)
                break
            time.sleep(0.05)
        s3["takeover_s_after_kill"] = round(takeover, 2) if takeover else None
        s3["killed_container"] = APP1
        wait = wait_drain(APPS[1])
        s3["drain_s_after_takeover"] = wait
    bg.join(timeout=LOAD_S * 3 + 40)
    s3["reconciliation_after"] = reconcile(APPS[1])
    s3["integrity_balanced"] = (jget(APPS[1], "/integrity") or {}).get("balanced")
    result["scenario3_relay_holder_killed"] = s3
    print("scenario3", json.dumps(s3, ensure_ascii=False), flush=True)
else:
    result["scenario3_relay_holder_killed"] = "생략: Redis 락이 꺼진 변형(redis-off)에서는 인계할 락 자체가 없다"

result["integrity_balanced_final"] = (jget(APPS[1] if MODE == "redis-on" else APPS[0], "/integrity") or {}).get("balanced")
with open(os.path.join(OUT, "multi-instance-%s.json" % MODE), "w", encoding="utf-8") as f:
    json.dump(result, f, ensure_ascii=False, indent=2)
print(json.dumps(result, ensure_ascii=False, indent=2))
