// ERD — 핵심만. 전체 표 관계는 docs/erd.svg 에 있고 이 그림은 한 문장만 남긴다.
//
// "인스턴스 하나에 관측·백업·변경 증거가 매달리고, 변경은 요청부터 실행까지 끊기지 않고 남는다."
//
// 그래서 여기 있는 것은 인스턴스와 그 세 갈래뿐이다. 설정 스냅샷·대화·경보 쿨다운처럼
// 같은 모양(인스턴스에 매달린 시계열)이 반복되는 표는 하나로 대표시키고 뺐다.
import { writeFileSync } from 'node:fs';
import { box, group, arrow, line, label, text, svg, C } from './rough-svg.mjs';

const W = 960, H = 620;
const p = [];

const card = (x, y, w, h, title, cols, opts = {}) => {
  p.push(box(x, y, w, h, { fill: opts.fill ?? C.paper, seed: opts.seed ?? 1, stroke: opts.stroke ?? C.line, strokeWidth: opts.strokeWidth ?? 1.6 }));
  p.push(label(x + w / 2, y + h / 2, title, cols, { titleSize: opts.titleSize ?? 13, fill: opts.titleFill ?? C.ink }));
};

// --- 가운데 허브
card(40, 250, 230, 110, 'database_instance', [
  'PK id · UQ name',
  'type · endpoint · team',
  '환경 · TLS · 수집 여부',
], { fill: C.tintBlue, seed: 11, titleSize: 14.5, strokeWidth: 2.2 });
p.push(text(155, 232, '모든 기록이 여기에 매달린다', { size: 11.5, weight: 600, fill: C.primary }));

// 1:N 표시를 한 곳에 모아 설명한다
const fan = (x1, y1, x2, y2, seed) => {
  p.push(arrow(x1, y1, x2, y2, { seed }));
  // 선의 수직 방향으로 비켜 놓는다 — 가운데에 그대로 두면 화살표가 글자를 가로지른다
  const a = Math.atan2(y2 - y1, x2 - x1);
  const off = 13;
  p.push(text(
    (x1 + x2) / 2 - off * Math.sin(a) * -1,
    (y1 + y2) / 2 + off * Math.cos(a) * -1 + 4,
    '1 : N', { size: 10.5, fill: C.muted },
  ));
};

// --- 갈래 1 · 관측
p.push(group(330, 40, 600, 128, { seed: 20 }));
p.push(text(346, 62, '관측 — 시간에 따라 쌓인다', { size: 12.5, weight: 600, fill: C.muted, anchor: 'start' }));
card(350, 76, 260, 78, 'query_snapshot_part', [
  'FK instance_id · query_id',
  '누적 카운터 · captured_at',
], { seed: 21 });
card(640, 76, 270, 78, '같은 모양의 표들', [
  'health_sample · plan_snapshot',
  'config_snapshot · ash_sample',
], { fill: C.tintGray, seed: 22, titleFill: C.muted });
p.push(text(630, 186, '월별 RANGE 파티션 — 오래된 구간을 통째로 떼어 낸다', { size: 10.5, fill: C.muted }));
fan(270, 282, 350, 130, 30);

// --- 갈래 2 · 변경의 증거 사슬
p.push(group(330, 196, 600, 210, { seed: 40, stroke: C.danger }));
p.push(text(346, 218, '변경의 증거 사슬 — 요청부터 실행까지 끊기지 않는다', {
  size: 12.5, weight: 600, fill: C.danger, anchor: 'start',
}));

card(350, 232, 175, 74, 'review_request', [
  '요청자 · 승인자',
  'SQL · 사유 · 상태',
], { fill: C.tintRed, seed: 41, stroke: C.danger, titleFill: C.danger });

card(553, 232, 175, 74, 'change_execution', [
  '티켓 · 실행자 · 상태',
  '행 사본 · 대조 · 되돌리기',
], { fill: C.tintRed, seed: 42, stroke: C.danger, titleFill: C.danger });

card(756, 232, 155, 74, 'bulk_change_run', [
  '수십만 행 경로',
  '현재 상태 · 마지막 키',
], { fill: C.tintRed, seed: 43, stroke: C.danger, titleFill: C.danger });

card(756, 322, 155, 68, 'bulk_change_batch', [
  '구간 · 영향 행 수',
  '그때의 복제 지연',
], { fill: C.paper, seed: 44, stroke: C.danger, titleFill: C.danger });

p.push(arrow(525, 269, 553, 269, { seed: 45, stroke: C.danger }));
p.push(arrow(728, 269, 756, 269, { seed: 46, stroke: C.danger }));
p.push(arrow(833, 306, 833, 322, { seed: 47, stroke: C.danger }));
p.push(text(853, 318, '1 : N', { size: 10.5, fill: C.muted, anchor: 'start' }));
fan(270, 300, 350, 269, 50);

// --- 갈래 3 · 백업
p.push(group(330, 434, 600, 126, { seed: 60 }));
p.push(text(346, 456, '백업 — 되돌릴 수 있다는 근거', { size: 12.5, weight: 600, fill: C.muted, anchor: 'start' }));
card(350, 470, 230, 74, 'backup_policy', [
  'FK instance_id · 종류',
  '주기 · 보관 기간',
], { seed: 61 });
card(610, 470, 300, 74, 'backup_run', [
  '상태 · 복원 검증 결과 · 원격 보관 위치',
  '대량 변경의 실행 전 조건이 이것이다',
], { seed: 62 });
p.push(arrow(580, 507, 610, 507, { seed: 63 }));
fan(270, 330, 350, 500, 70);

// --- 아래 한 줄
p.push(line(40, 585, 920, 585, { seed: 80, stroke: '#9aa5b1', strokeWidth: 1.2 }));
p.push(text(480, 606, '승인된 SQL 과 실행된 SQL 이 같다는 것을 표로 증명할 수 있어야 한다 — 이 사슬이 그것이다', {
  size: 12, fill: C.muted,
}));

writeFileSync(
  new URL('../erd-core.svg', import.meta.url),
  svg(W, H, p.join('\n'), 'DBTower 데이터 모델 핵심'),
);
console.log('docs/erd-core.svg');
