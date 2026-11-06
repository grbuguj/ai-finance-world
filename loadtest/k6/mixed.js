// 시나리오 3: 혼합 — 소비 60% (가맹점 5곳 분산) / 송금 40%
import http from 'k6/http';
import { check } from 'k6';
import { BASE_URL, baseOptions, prepare, pick, pickTwo, saveSummary } from './common.js';

export const options = baseOptions();
export function setup() { return prepare(); }

export default function (data) {
  let res;
  if (Math.random() < 0.6) {
    res = http.post(`${BASE_URL}/spends?from=${pick(data.accountIds)}&merchant=${pick(data.merchantIds)}&amount=1000&description=k6`,
      null, { tags: { op: 'spend' } });
  } else {
    const [from, to] = pickTwo(data.accountIds);
    res = http.post(`${BASE_URL}/transfers?from=${from}&to=${to}&amount=1000&description=k6`,
      null, { tags: { op: 'transfer' } });
  }
  check(res, { 'status 200': (r) => r.status === 200 });
}

export function handleSummary(data) { return saveSummary('mixed', data); }
