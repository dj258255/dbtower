// 아키텍처 — 한눈에 읽히는 것이 목적이다.
//
// 담는 것은 넷. 입구가 사람과 에이전트 둘, 조회와 변경이 다른 계정으로 갈림,
// AI 는 판단자가 아니라 1차 분석기, 플랫폼 저장소와 대상 DB 의 분리.
// 모듈 17개를 다 그리지 않는다 — 그건 docs/modules/ 가 이미 정확하게 한다.
import { writeFileSync } from 'node:fs';
import { card, text, stack, elbow, elbowH, tag, group, svg, C } from './flat-svg.mjs';

const W = 1180, H = 700;
const p = [];

const title = (x, y, s, sub) => {
  p.push(text(x, y, s, { size: 19, weight: 700, fill: C.ink, anchor: 'start' }));
  if (sub) p.push(text(x, y + 22, sub, { size: 12.5, fill: C.muted, anchor: 'start' }));
};

title(40, 40, 'DBTower', 'Java 21 · Spring Boot 4 · Spring Modulith 17 모듈 — 경계는 빌드가 검사한다');

// ── 입구 둘
const ENTRY_Y = 100;
p.push(card(40, ENTRY_Y, 300, 76, { fill: C.slate, stroke: C.line }));
p.push(stack(190, ENTRY_Y + 38, '운영자 · 개발자', ['역할 5종 · 로그인과 팀 범위']));

p.push(card(840, ENTRY_Y, 300, 76, { fill: C.slate, stroke: C.line }));
p.push(stack(990, ENTRY_Y + 38, 'AI 에이전트 (MCP)', ['조회와 요청만 · 실행 도구 없음']));

// ── 세 갈래
const BAND_Y = 232, BAND_H = 104;
p.push(elbow([190, ENTRY_Y + 76], [190, BAND_Y]));
p.push(elbow([990, ENTRY_Y + 76], [990, BAND_Y]));

p.push(card(40, BAND_Y, 300, BAND_H, { fill: C.blueSoft, stroke: C.blueLine }));
p.push(stack(190, BAND_Y + 52, '조회', ['분리된 조회 계정', '읽기 전용 트랜잭션 · 마스킹'], { titleFill: C.blue }));

p.push(card(380, BAND_Y, 420, BAND_H, { fill: C.redSoft, stroke: C.redLine, strokeWidth: 1.5 }));
p.push(stack(590, BAND_Y + 52, '변경', [
  '승인된 티켓만 실행 · 요청자와 승인자와 운영자가 다른 사람',
  '영향 행 수가 사본과 어긋나면 커밋하지 않는다',
], { titleFill: C.red }));

p.push(card(840, BAND_Y, 300, BAND_H, { fill: C.blueSoft, stroke: C.blueLine }));
p.push(stack(990, BAND_Y + 52, 'AI 1차 분석', ['판단 기준은 사람이 정한다', '모델이 낸 수치는 사실과 대조'], { titleFill: C.blue }));

// ── 한 인터페이스
const IFACE_Y = 400;
p.push(elbow([190, BAND_Y + BAND_H], [480, IFACE_Y], { mid: BAND_Y + BAND_H + 26 }));
p.push(elbow([590, BAND_Y + BAND_H], [590, IFACE_Y]));
p.push(elbow([990, BAND_Y + BAND_H], [700, IFACE_Y], { mid: BAND_Y + BAND_H + 26 }));

p.push(card(330, IFACE_Y, 520, 74, { fill: C.paper, stroke: C.blue, strokeWidth: 1.6, shadow: true }));
p.push(stack(590, IFACE_Y + 37, 'DbmsOperator', [
  '플랫폼 코드는 기종을 모른다 · 구현 선택은 팩토리 한 곳',
], { titleSize: 16, titleFill: C.blue, mono: true }));

// ── 아래 둘
// 묶음 상자의 위 테두리에서 화살표를 끊는다 — 안으로 들어가면 제목 글자를 가린다
const GROUP_Y = 552, BOT_Y = GROUP_Y + 26;
p.push(elbow([470, IFACE_Y + 74], [300, GROUP_Y], { mid: IFACE_Y + 104 }));
p.push(tag(300, IFACE_Y + 104, '조회 계정 · 변경 계정을 나눠 접속'));
p.push(elbow([760, IFACE_Y + 74], [935, GROUP_Y], { mid: IFACE_Y + 104 }));
p.push(tag(935, IFACE_Y + 104, '자기 상태만'));

p.push(group(40, GROUP_Y, 660, 112, '관리 대상 DB — 새 기종은 Operator 구현체 하나가 본체'));
['MySQL', 'PostgreSQL', 'SQL Server', 'Oracle', 'MongoDB'].forEach((name, i) => {
  const x = 56 + i * 126;
  p.push(card(x, BOT_Y + 6, 112, 52, { fill: C.paper, stroke: C.line }));
  p.push(text(x + 56, BOT_Y + 25, name, { size: 12.5, weight: 600, fill: C.ink }));
  p.push(text(x + 56, BOT_Y + 41, i === 4 ? '비 JDBC' : 'JDBC', { size: 10.5, fill: C.muted }));
});

p.push(card(790, GROUP_Y, 350, 112, { fill: C.slate, stroke: C.line }));
p.push(stack(965, BOT_Y + 30, '플랫폼 저장소', [
  'PostgreSQL · Flyway',
  '대상 장애가 플랫폼을 죽이지 않게 분리한다',
]));

// ── 한 줄 결론
p.push(text(40, H - 26, '조회는 읽기 전용 계정으로, 변경은 승인된 티켓만 — 이 둘이 이 플랫폼의 불변식이다.',
  { size: 12.5, fill: C.muted, anchor: 'start' }));

writeFileSync(new URL('./architecture.svg', import.meta.url), svg(W, H, p.join('\n'), 'DBTower 아키텍처'));
console.log('docs/diagrams/architecture.svg');
