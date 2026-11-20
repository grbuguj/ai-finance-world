#!/usr/bin/env python3
"""
④(b) MySQL 재시작 실험 — 부하(HTTP 동시 송금) 중에 MySQL 컨테이너를 멈췄다 켜고 다음을 기록한다.

  - 중단 시간, 구간별(DB 정지 중 / 기동 중 / 복구 후) 요청 수·실패 수·응답 코드 분포·지연
  - 복구까지 걸린 시간: docker start 후 첫 성공까지, DB 가 응답 가능해진 시점부터 첫 성공까지
  - 사후: Outbox 대기 0 이 되기까지, 원장 무결성, 대사 MATCHED 여부

표준 라이브러리만 쓴다. 앱은 docker compose (--profile app, 프로필 infra,loadtest) 로 떠 있어야 한다.
환경변수: BASE_URL, OUT, THREADS, PRE_S, OUTAGE_S, POST_S, MYSQL_CONTAINER
"""
import json
import os
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
from collections import Counter

BASE = os.environ.get("BASE_URL", "http://localhost:8080")
OUT = os.environ.get("OUT", "results/db-fault")
THREADS = int(os.environ.get("THREADS", "20"))
PRE_S = float(os.environ.get("PRE_S", "15"))
OUTAGE_S = float(os.environ.get("OUTAGE_S", "20"))
POST_S = float(os.environ.get("POST_S", "20"))
MYSQL = os.environ.get("MYSQL_CONTAINER", "ai-finance-mysql")
os.makedirs(OUT, exist_ok=True)


def http(method, path, timeout=30):
    req = urllib.request.Request(BASE + path, method=method, data=b"" if method == "POST" else None)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, r.read()
    except urllib.error.HTTPError as e:
        return e.code, e.read()
    except Exception as e:  # 연결 거부·타임아웃 등
        return "ERR:" + type(e).__name__, b""


def sh(cmd):
    t0 = time.time()
    p = subprocess.run(cmd, shell=True, capture_output=True, text=True)
    return time.time(), time.time() - t0, p.returncode, (p.stdout + p.stderr).strip()


def pct(values, q):
    if not values:
        return None
    s = sorted(values)
    return round(s[min(len(s) - 1, int(len(s) * q))] * 1000, 1)


# ── 준비: 부하용 계좌 (loadtest 프로필) ─────────────────────────────────────────
code, body = http("POST", "/loadtest/setup?accounts=100&prefix=DBF%d" % int(time.time()), timeout=120)
if code != 200:
    print("loadtest/setup 실패:", code, body[:300])
    sys.exit(1)
accounts = json.loads(body)["accountIds"]

records = []  # (시작 시각, 지연 초, 상태)
stop = threading.Event()


def worker(seed):
    i = seed
    while not stop.is_set():
        a = accounts[i % len(accounts)]
        b = accounts[(i * 7 + 3) % len(accounts)]
        i += 13
        if a == b:
            continue
        t = time.time()
        status, _ = http("POST", "/transfers?from=%d&to=%d&amount=1000&description=dbfault" % (a, b), timeout=30)
        records.append((t, time.time() - t, status))


threads = [threading.Thread(target=worker, args=(k,), daemon=True) for k in range(THREADS)]
t0 = time.time()
for th in threads:
    th.start()
time.sleep(PRE_S)

# ── 장애: MySQL 정지 → OUTAGE_S 초 유지 → 시작 ──────────────────────────────────
t_stop_begin = time.time()
_, stop_took, rc_stop, out_stop = sh("docker stop %s" % MYSQL)
t_stop_end = time.time()
print("docker stop rc=%s took=%.1fs" % (rc_stop, stop_took), flush=True)
time.sleep(max(0.0, OUTAGE_S - (time.time() - t_stop_end)))

t_start = time.time()
sh("docker start %s" % MYSQL)
ready = {"t": None}


def wait_ready():
    while ready["t"] is None and time.time() - t_start < 180:
        _, _, rc, _ = sh("docker exec %s mysqladmin ping -h localhost -ufinance -pfinance1234 --silent" % MYSQL)
        if rc == 0:
            ready["t"] = time.time()
            return
        time.sleep(0.5)


rt = threading.Thread(target=wait_ready, daemon=True)
rt.start()

t_first_ok = None
deadline = time.time() + 150
while time.time() < deadline:
    ok = [r for r in list(records) if r[2] == 200 and r[0] >= t_start]
    if ok:
        t_first_ok = min(r[0] + r[1] for r in ok)  # 응답을 받은 시각
        break
    time.sleep(0.2)
time.sleep(POST_S)
stop.set()
for th in threads:
    th.join(timeout=40)
t_end = time.time()
rt.join(timeout=5)


def window(lo, hi):
    rs = [r for r in records if lo <= r[0] < hi]
    dist = Counter(str(r[2]) for r in rs)
    fails = [r for r in rs if r[2] != 200]
    return {
        "requests": len(rs),
        "success": len(rs) - len(fails),
        "failed": len(fails),
        "status_distribution": dict(dist),
        "latency_ms_all": {"p50": pct([r[1] for r in rs], 0.5), "p95": pct([r[1] for r in rs], 0.95), "max": pct([r[1] for r in rs], 1.0)},
        "latency_ms_failed": {"p50": pct([r[1] for r in fails], 0.5), "p95": pct([r[1] for r in fails], 0.95), "max": pct([r[1] for r in fails], 1.0)},
    }


first_ok_abs = t_first_ok if t_first_ok else t_end
result = {
    "config": {"threads": THREADS, "pre_s": PRE_S, "outage_target_s": OUTAGE_S, "post_s": POST_S},
    "timeline_s": {
        "docker_stop_took": round(stop_took, 1),
        "mysql_down_duration(stop_end→docker_start)": round(t_start - t_stop_end, 1),
        "docker_start→db_ready": round(ready["t"] - t_start, 1) if ready["t"] else None,
        "docker_start→first_success": round(first_ok_abs - t_start, 1) if t_first_ok else None,
        "db_ready→first_success": round(t_first_ok - ready["t"], 1) if (t_first_ok and ready["t"]) else None,
        "total_run": round(t_end - t0, 1),
    },
    "windows": {
        "before_outage": window(t0, t_stop_begin),
        "stopping(docker stop 진행 중)": window(t_stop_begin, t_stop_end),
        "db_down": window(t_stop_end, t_start),
        "db_starting(docker start→첫 성공)": window(t_start, first_ok_abs),
        "after_recovery": window(first_ok_abs, t_end),
    },
}

# ── 사후 검증 ───────────────────────────────────────────────────────────────────
drain_t0 = time.time()
pending = None
for _ in range(120):
    code, body = http("GET", "/ops/outbox")
    if code == 200:
        pending = json.loads(body)
        if pending["pending"] == 0:
            break
    time.sleep(1)
result["post"] = {"outbox": pending, "outbox_drain_s": round(time.time() - drain_t0, 1)}
code, body = http("GET", "/integrity")
result["post"]["integrity_balanced"] = json.loads(body).get("balanced") if code == 200 else code
recon = None
for _ in range(30):
    code, body = http("POST", "/ops/reconciliation")
    if code == 200:
        recon = json.loads(body)
        if recon["status"] == "MATCHED":
            break
    time.sleep(2)
if recon:
    recon.pop("breaks", None)
result["post"]["reconciliation"] = recon

with open(os.path.join(OUT, "db-fault.json"), "w", encoding="utf-8") as f:
    json.dump(result, f, ensure_ascii=False, indent=2)
print(json.dumps(result, ensure_ascii=False, indent=2))
