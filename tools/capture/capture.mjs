// 발표 자료용 실제 화면 캡처 (screenshots.yml 에서 실행).
// 앱·Grafana 는 CI 러너에서 docker compose 로 띄운 실제 스택, GitHub 화면은 공개 레포의 실제 페이지.
import { chromium } from 'playwright';
import fs from 'node:fs';

const OUT = process.env.OUT || 'docs/screenshots';
const APP = 'http://localhost:8080';
const mode = process.argv[2];
fs.mkdirSync(OUT, { recursive: true });

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const browser = await chromium.launch();
const ctx = await browser.newContext({ viewport: { width: 1920, height: 1080 }, deviceScaleFactor: 1, locale: 'ko-KR' });
const page = await ctx.newPage();

async function shot(name, url, waitMs = 6000, opts = {}) {
  await page.goto(url, { waitUntil: 'networkidle', timeout: 60000 }).catch(() => {});
  await sleep(waitMs);
  await page.screenshot({ path: `${OUT}/${name}.png`, ...opts });
  console.log('captured', name);
}

async function shotElement(name, selector) {
  const el = await page.$(selector);
  if (el) { await el.screenshot({ path: `${OUT}/${name}.png` }); console.log('captured', name); }
  else console.log('missing selector', selector);
}

if (mode === 'dashboard') {
  await shot('dashboard', APP, 9000);
  await shotElement('control-room', '.reliab');
} else if (mode === 'chaos-down') {
  await shot('dashboard-broker-down', APP, 7000);
  await shotElement('control-room-broker-down', '.reliab');
} else if (mode === 'chaos-up') {
  await shot('dashboard-recovered', APP, 7000);
  await shotElement('control-room-recovered', '.reliab');
} else if (mode === 'grafana') {
  await shot('grafana', 'http://localhost:3000/d/afw-main?orgId=1&from=now-15m&to=now&kiosk', 15000);
} else if (mode === 'terminal') {
  // 실제 k6 출력(텍스트)을 터미널 모양으로 렌더링해 캡처
  const text = fs.readFileSync(process.argv[3], 'utf8').replace(/\x1b\[[0-9;]*m/g, '');
  const esc = text.replace(/&/g, '&amp;').replace(/</g, '&lt;');
  await page.setViewportSize({ width: 1600, height: 1000 });
  await page.setContent(`<html><body style="margin:0;background:#0d1117;"><pre style="margin:0;padding:32px 40px;color:#e6edf3;font:18px/1.5 'DejaVu Sans Mono',monospace;white-space:pre;">${esc}</pre></body></html>`);
  await page.screenshot({ path: `${OUT}/${process.argv[4]}.png`, fullPage: true });
  console.log('captured', process.argv[4]);
} else if (mode === 'github') {
  const pages = JSON.parse(process.argv[3]);
  for (const [name, url] of Object.entries(pages)) await shot(name, url, 5000);
}
await browser.close();
