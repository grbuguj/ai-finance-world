// 시나리오 2: 소비 집중 — 모든 결제가 가맹점 1곳으로 (핫스팟: 가맹점 계좌 행 락 경합)
import http from 'k6/http';
import { check } from 'k6';
import { BASE_URL, baseOptions, prepare, pick, saveSummary } from './common.js';

export const options = baseOptions();
export function setup() { return prepare(); }

export default function (data) {
  const from = pick(data.accountIds);
  const merchant = data.merchantIds[0];
  const res = http.post(`${BASE_URL}/spends?from=${from}&merchant=${merchant}&amount=1000&description=k6`);
  check(res, { 'status 200': (r) => r.status === 200 });
}

export function handleSummary(data) { return saveSummary('spend-hotspot', data); }
