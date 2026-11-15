// 시나리오 1: 송금만 — 계좌 100개 사이 무작위 송금 (경합 낮음)
import http from 'k6/http';
import { check } from 'k6';
import { BASE_URL, baseOptions, prepare, pickTwo, saveSummary } from './common.js';

export const options = baseOptions();
export function setup() { return prepare(); }

export default function (data) {
  const [from, to] = pickTwo(data.accountIds);
  const res = http.post(`${BASE_URL}/transfers?from=${from}&to=${to}&amount=1000&description=k6`);
  check(res, { 'status 200': (r) => r.status === 200 });
}

export function handleSummary(data) { return saveSummary('transfer', data); }
