// 발견에서 실행까지 — 책임이 어디서 어디로 넘어가는지가 이 그림의 내용이다.
//
// 가로 한 줄로 흐르게 둔다. 앞 판은 세로로 쌓이고 곡선이 겹쳐 순서를 읽을 수 없었다.
// 색은 셋만 쓴다: 사실 수집(회색) · AI(파랑) · 사람과 불변식(빨강).
import { writeFileSync } from 'node:fs';
import { card, text, stack, elbowH, elbow, tag, group, svg, C } from './flat-svg.mjs';

const W = 1180, H = 500;
const p = [];

p.push(text(40, 40, '발견에서 실행까지', { size: 19, weight: 700, fill: C.ink, anchor: 'start' }));
p.push(text(40, 62, 'AI 는 근거를 모아 1차 소견까지. 승인은 사람이, 실행의 안전은 불변식이 맡는다', {
  size: 12.5, fill: C.muted, anchor: 'start',
}));

const step = (x, y, w, h, n, name, lines, o = {}) => {
  p.push(card(x, y, w, h, { fill: o.fill ?? C.paper, stroke: o.stroke ?? C.line, strokeWidth: o.strokeWidth ?? 1 }));
  p.push(text(x + 14, y + 18, n, { size: 10.5, weight: 700, fill: o.numFill ?? C.muted, anchor: 'start' }));
  p.push(stack(x + w / 2, y + h / 2 + 6, name, lines, { titleFill: o.nameFill ?? C.ink, gap: 15 }));
};

// ── 윗줄: 사실을 모은다
const TOP_Y = 110, SH = 92, SW = 250;
p.push(group(40, TOP_Y - 26, 1100, 148, '관측에서 변경 요청까지 — 여기까지는 아무것도 바꾸지 않는다'));

step(60, TOP_Y, SW, SH, '1', '발견', ['헬스 · Top Query · 경보']);
step(60 + 270, TOP_Y, SW, SH, '2', '비교', ['두 구간의 누적 카운터 차분']);
step(60 + 540, TOP_Y, SW, SH, '3', '결정론적 근거', ['실행계획 · 스키마', 'wait · 복제 상태']);
step(60 + 810, TOP_Y, 270, SH, '4', 'AI 1차 분석', ['읽기 도구만 고른다', '판단 기준은 사람이 정한 문서'],
  { fill: C.blueSoft, stroke: C.blueLine, nameFill: C.blue, numFill: C.blue });

[0, 1, 2].forEach((i) => p.push(elbowH([60 + SW + i * 270, TOP_Y + SH / 2], [60 + 270 + i * 270, TOP_Y + SH / 2])));

// ── 아랫줄: 사람과 불변식
const BOT_Y = 306;
p.push(group(40, BOT_Y - 26, 1100, 158, '사람의 승인과 실행 계층의 검증 — 여기서만 바뀐다',
  { fill: C.redSoft, stroke: C.redLine }));

step(60, BOT_Y, SW, SH, '5', '변경 요청', ['SQL · 이유 · 예상 영향'],
  { stroke: C.redLine, nameFill: C.red, numFill: C.red });
step(60 + 270, BOT_Y, SW, SH, '6', '승인 (APPROVER)', ['드라이런으로 먼저 본다'],
  { stroke: C.redLine, nameFill: C.red, numFill: C.red });
step(60 + 540, BOT_Y, SW, SH, '7', '실행 (OPERATOR)', ['승인자와 다른 사람'],
  { stroke: C.redLine, nameFill: C.red, numFill: C.red });
step(60 + 810, BOT_Y, 270, SH, '8', '결과 고정', ['감사 · 실행 기록 · 전후 비교'],
  { stroke: C.redLine, nameFill: C.red, numFill: C.red });

[0, 1, 2].forEach((i) => p.push(elbowH([60 + SW + i * 270, BOT_Y + SH / 2], [60 + 270 + i * 270, BOT_Y + SH / 2],
  { stroke: C.red })));

// 4 -> 5 로 내려오는 연결. 윗줄 끝에서 아랫줄 처음으로 돌아오므로 그림을 가로지른다 —
// 그 가로지름 자체가 "여기서 읽기가 끝나고 쓰기가 시작된다"는 뜻이라 감추지 않는다
p.push(elbow([1005, TOP_Y + SH], [185, BOT_Y], { mid: TOP_Y + SH + 48, stroke: C.red }));
p.push(tag(595, TOP_Y + SH + 48, 'AI 가 낸 소견은 여기서 사람의 요청이 된다 — 모델이 실행하지 않는다',
  { fill: C.red }));

// 불변식 한 줄
p.push(text(40, BOT_Y + SH + 60,
  '실행의 안전은 문장 해석이 아니라 불변식에서 나온다 — 사본 행 수와 영향 행 수가 어긋나면 커밋하지 않는다.',
  { size: 12.5, fill: C.muted, anchor: 'start' }));

writeFileSync(new URL('./insight-flow.svg', import.meta.url), svg(W, H, p.join('\n'), 'DBTower 발견에서 실행까지'));
console.log('docs/diagrams/insight-flow.svg');
