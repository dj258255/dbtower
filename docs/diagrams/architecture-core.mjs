// 아키텍처 — 핵심만. 상세본은 docs/architecture-detail.svg(모듈 17개)에 있고
// 이 그림은 "이 플랫폼이 무엇을 보장하는가"만 남긴다.
//
// 남긴 것 넷: 입구가 둘이라는 것, 조회와 변경이 다른 계정으로 갈린다는 것,
// AI 는 판단자가 아니라 1차 분석기라는 것, 플랫폼 저장소와 대상 DB 가 분리돼 있다는 것.
// 이 넷이 DESIGN.md 의 원칙이고, 나머지(모듈 이름·화면·수집 주기)는 여기서 뺀다.
import { writeFileSync } from 'node:fs';
import { box, group, arrow, label, text, svg, C } from './rough-svg.mjs';

const W = 900, H = 600;
const p = [];

// --- 입구 둘
p.push(box(60, 30, 250, 62, { fill: C.tintGray, seed: 11 }));
p.push(label(185, 61, '운영자 · 개발자', ['역할 5종 — 조회자·요청자·승인자·운영자·관리자']));

p.push(box(590, 30, 250, 62, { fill: C.tintGray, seed: 12 }));
p.push(label(715, 61, 'AI 에이전트', ['MCP — 조회와 요청만, 실행 도구는 없다']));

p.push(arrow(185, 92, 185, 133, { seed: 20 }));
p.push(text(196, 117, '로그인 · 역할', { size: 11, fill: C.muted, anchor: 'start' }));
p.push(arrow(715, 92, 715, 133, { seed: 23 }));
p.push(text(704, 117, '토큰', { size: 11, fill: C.muted, anchor: 'end' }));

// --- 플랫폼
p.push(group(40, 135, 820, 250, { seed: 30 }));
p.push(text(56, 157, 'DBTower · Java 21 / Spring Boot 4 / Spring Modulith 17 모듈', {
  size: 12.5, weight: 600, fill: C.muted, anchor: 'start',
}));

p.push(box(70, 175, 235, 80, { fill: C.tintBlue, seed: 31 }));
p.push(label(187, 215, '조회', ['분리된 조회 계정', '읽기 전용 트랜잭션']));

p.push(box(332, 175, 235, 80, { fill: C.tintRed, seed: 32, stroke: C.danger }));
p.push(label(449, 215, '변경', ['승인된 티켓만 실행', '영향 행 수가 어긋나면 커밋 안 함'], { fill: C.danger }));

p.push(box(594, 175, 235, 80, { fill: C.tintBlue, seed: 33 }));
p.push(label(711, 215, 'AI 1차 분석', ['판단 기준은 사람이 정한다', '모델 수치는 사실과 대조']));

p.push(box(210, 285, 480, 72, { fill: C.paper, seed: 34, strokeWidth: 2 }));
p.push(label(450, 321, 'DbmsOperator — 능력 하나의 인터페이스', [
  '플랫폼 코드는 기종을 모른다. 구현 선택은 팩토리 한 곳',
]));

p.push(arrow(187, 255, 300, 285, { seed: 40 }));
p.push(arrow(449, 255, 449, 285, { seed: 43 }));
p.push(arrow(711, 255, 600, 285, { seed: 46 }));

// --- 대상 DB
p.push(arrow(380, 357, 300, 432, { seed: 50 }));
p.push(text(283, 400, '조회 계정 · 변경 계정을 나눠 접속', { size: 11, fill: C.muted, anchor: 'end' }));

p.push(group(40, 434, 560, 128, { seed: 55 }));
p.push(text(56, 456, '관리 대상 DB — 새 기종은 구현체 하나가 본체', {
  size: 12.5, weight: 600, fill: C.muted, anchor: 'start',
}));
const targets = ['MySQL', 'PostgreSQL', 'SQL Server', 'Oracle', 'MongoDB'];
targets.forEach((name, i) => {
  const x = 60 + i * 107;
  p.push(box(x, 470, 98, 62, { fill: C.paper, seed: 60 + i }));
  p.push(text(x + 49, 500, name, { size: 12.5, weight: 600 }));
  p.push(text(x + 49, 517, i === 4 ? '비 JDBC' : 'JDBC', { size: 10.5, fill: C.muted }));
});

// --- 플랫폼 저장소
p.push(arrow(560, 357, 700, 432, { seed: 70 }));
p.push(text(716, 400, '자기 상태만', { size: 11, fill: C.muted, anchor: 'start' }));

p.push(box(640, 434, 220, 96, { fill: C.tintGray, seed: 75 }));
p.push(label(750, 482, '플랫폼 저장소', [
  'PostgreSQL · Flyway',
  '대상 장애가 플랫폼을',
  '죽이지 않게 분리',
]));

p.push(text(450, 583, '조회는 읽기 전용 계정으로, 변경은 승인된 티켓만 — 이 둘이 이 플랫폼의 불변식이다', {
  size: 12, fill: C.muted,
}));

writeFileSync(
  new URL('../architecture-core.svg', import.meta.url),
  svg(W, H, p.join('\n'), 'DBTower 아키텍처 핵심'),
);
console.log('docs/architecture-core.svg');
