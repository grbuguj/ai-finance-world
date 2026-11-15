// 공통 설정 / 준비 단계
import http from 'k6/http';

export const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
export const VUS = Number(__ENV.VUS || 50);
export const DURATION = __ENV.DURATION || '60s';
export const ACCOUNTS = Number(__ENV.ACCOUNTS || 100);
export const LABEL = __ENV.LABEL || 'local';

export function baseOptions() {
  return {
    scenarios: {
      main: { executor: 'constant-vus', vus: VUS, duration: DURATION },
    },
    summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
    thresholds: {
      // 측정이 목적이므로 실패해도 중단하지 않는다 (기록만)
      http_req_failed: [{ threshold: 'rate<0.5', abortOnFail: false }],
    },
  };
}

/** 시뮬레이션 정지 + 부하용 계좌 생성 (loadtest 프로필 필요) */
export function prepare() {
  const prefix = 'LT' + Date.now();
  const res = http.post(`${BASE_URL}/loadtest/setup?accounts=${ACCOUNTS}&prefix=${prefix}`, null, { timeout: '120s' });
  if (res.status !== 200) {
    throw new Error(`setup 실패 (${res.status}) — 앱을 loadtest 프로필로 실행했는지 확인: ${res.body}`);
  }
  return res.json();
}

export function pick(arr) {
  return arr[Math.floor(Math.random() * arr.length)];
}

export function pickTwo(arr) {
  const a = pick(arr);
  let b = pick(arr);
  while (b === a) b = pick(arr);
  return [a, b];
}

/** 결과를 loadtest/results/<시나리오>-<LABEL>.json 으로 저장 */
export function saveSummary(name, data) {
  const out = {};
  out[`loadtest/results/${name}-${LABEL}.json`] = JSON.stringify(data, null, 2);
  out.stdout = textSummary(name, data);
  return out;
}

function textSummary(name, data) {
  const d = data.metrics.http_req_duration.values;
  const reqs = data.metrics.http_reqs.values;
  const failed = data.metrics.http_req_failed.values.rate;
  return `\n[${name} / ${LABEL}] TPS=${reqs.rate.toFixed(1)}  avg=${d.avg.toFixed(1)}ms  ` +
    `p95=${d['p(95)'].toFixed(1)}ms  p99=${d['p(99)'].toFixed(1)}ms  실패율=${(failed * 100).toFixed(2)}%\n`;
}
