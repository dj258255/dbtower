// 데이터 모델 — 한 문장으로 읽히는 것이 목적이다.
//
// "인스턴스 하나에 관측·백업·변경 증거가 매달리고, 변경은 요청부터 실행까지 한 사슬로 남는다."
//
// 표를 다 그리지 않는다. 같은 모양(인스턴스에 매달린 시계열)이 반복되는 것은 하나로 대표시킨다 —
// 앞 판의 자동 배치 ERD 는 1500x3321 로 늘어나 화면에 담기지도 않았다.
import { writeFileSync } from 'node:fs';
import { card, text, stack, elbowH, tag, group, svg, C } from './flat-svg.mjs';

const W = 1180, H = 660;
const p = [];

p.push(text(40, 40, '데이터 모델', { size: 19, weight: 700, fill: C.ink, anchor: 'start' }));
p.push(text(40, 62, '인스턴스 하나에 세 갈래가 매달린다 — 관측, 변경의 증거, 백업', {
  size: 12.5, fill: C.muted, anchor: 'start',
}));

/** 표 한 칸 — 이름은 코드 글꼴로, 열은 아래에 작게. */
const table = (x, y, w, h, name, cols, o = {}) => {
  p.push(card(x, y, w, h, { fill: o.fill ?? C.paper, stroke: o.stroke ?? C.line, strokeWidth: o.strokeWidth ?? 1 }));
  p.push(text(x + w / 2, y + 24, name, { size: 13.5, weight: 600, fill: o.nameFill ?? C.ink, mono: true }));
  cols.forEach((c, i) => p.push(text(x + w / 2, y + 46 + i * 16, c, { size: 11, fill: C.muted })));
};

// ── 허브
const HUB_X = 40, HUB_Y = 268, HUB_W = 240, HUB_H = 116;
table(HUB_X, HUB_Y, HUB_W, HUB_H, 'database_instance', [
  'PK id · UQ name',
  'type · endpoint · team',
  '환경 · TLS · 수집 여부',
], { fill: C.blueSoft, stroke: C.blue, strokeWidth: 1.6, nameFill: C.blue });
p.push(text(HUB_X + HUB_W / 2, HUB_Y - 16, '모든 기록이 여기에 매달린다', {
  size: 11.5, weight: 600, fill: C.blue,
}));

const RIGHT = HUB_X + HUB_W;          // 허브 오른쪽 변
const FORK = 330;                     // 세 갈래가 갈라지는 세로선

// ── 갈래 1 · 관측
p.push(group(FORK + 30, 96, 780, 122, '관측 — 시간에 따라 쌓인다'));
table(FORK + 50, 124, 300, 76, 'query_snapshot_part', [
  'FK instance_id · query_id · 누적 카운터',
]);
table(FORK + 370, 124, 400, 76, '같은 모양의 표들', [
  'health_sample · plan_snapshot · config_snapshot · ash_sample',
], { fill: C.slate, nameFill: C.muted });
p.push(text(FORK + 420, 236, '월별 RANGE 파티션 — 오래된 구간을 통째로 떼어 낸다', {
  size: 11, fill: C.muted,
}));
p.push(elbowH([RIGHT, HUB_Y + 20], [FORK + 50, 162], { mid: FORK }));
p.push(tag(FORK, 162, '1 : N'));

// ── 갈래 2 · 변경의 증거 사슬
const CH_Y = 268;
p.push(group(FORK + 30, CH_Y, 780, 158, '변경의 증거 사슬 — 요청부터 실행까지 끊기지 않는다',
  { fill: C.redSoft, stroke: C.redLine }));
table(FORK + 50, CH_Y + 30, 220, 74, 'review_request', [
  '요청자 · 승인자',
  'SQL · 사유 · 상태',
], { stroke: C.redLine, nameFill: C.red });
table(FORK + 305, CH_Y + 30, 220, 74, 'change_execution', [
  '티켓 · 실행자 · 상태',
  '행 사본 · 대조 · 되돌리기',
], { stroke: C.redLine, nameFill: C.red });
table(FORK + 560, CH_Y + 30, 210, 74, 'bulk_change_run', [
  '수십만 행 경로',
  '현재 상태 · 마지막 키',
], { stroke: C.redLine, nameFill: C.red });
p.push(elbowH([FORK + 270, CH_Y + 67], [FORK + 305, CH_Y + 67], { stroke: C.red }));
p.push(elbowH([FORK + 525, CH_Y + 67], [FORK + 560, CH_Y + 67], { stroke: C.red }));

p.push(text(FORK + 790, CH_Y + 124, 'bulk_change_run 1 : N bulk_change_batch — 구간 · 영향 행 수 · 그때의 복제 지연', {
  size: 11, fill: C.red, anchor: 'end',
}));
p.push(elbowH([RIGHT, HUB_Y + 58], [FORK + 50, CH_Y + 67], { mid: FORK }));
p.push(tag(FORK, CH_Y + 67, '1 : N'));

// ── 갈래 3 · 백업
const BK_Y = 460;
p.push(group(FORK + 30, BK_Y, 780, 122, '백업 — 되돌릴 수 있다는 근거'));
table(FORK + 50, BK_Y + 28, 300, 76, 'backup_policy', [
  'FK instance_id · 종류 · 주기 · 보관',
]);
table(FORK + 370, BK_Y + 28, 400, 76, 'backup_run', [
  '상태 · 복원 검증 결과 · 원격 보관 위치',
], { nameFill: C.ink });
p.push(elbowH([FORK + 350, BK_Y + 66], [FORK + 370, BK_Y + 66]));
p.push(elbowH([RIGHT, HUB_Y + 96], [FORK + 50, BK_Y + 66], { mid: FORK }));
p.push(tag(FORK, BK_Y + 66, '1 : N'));

p.push(text(40, H - 26,
  '승인된 SQL 과 실행된 SQL 이 같다는 것을 표로 증명할 수 있어야 한다 — 이 사슬이 그것이다. '
  + '복원 검증에 성공한 백업은 대량 변경의 실행 전 조건이다.',
  { size: 12.5, fill: C.muted, anchor: 'start' }));

writeFileSync(new URL('./erd.svg', import.meta.url), svg(W, H, p.join('\n'), 'DBTower 데이터 모델'));
console.log('docs/diagrams/erd.svg');
