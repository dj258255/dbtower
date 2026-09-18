// CI 부터 셀프호스트 런타임까지 — 무엇이 게이트이고 무엇이 산출물인지가 이 그림의 내용이다.
//
// 게이트는 붉게, 산출물은 파랗게, 나머지는 회색. 세 줄로 나눠 가로로 흐르게 둔다.
import { writeFileSync } from 'node:fs';
import { card, text, stack, elbowH, elbow, tag, group, svg, C } from './flat-svg.mjs';

const W = 1180, H = 560;
const p = [];

p.push(text(40, 40, '변경이 합쳐지고 게시되고 도는 곳', { size: 19, weight: 700, fill: C.ink, anchor: 'start' }));
p.push(text(40, 62, '게이트는 붉게, 게시물은 파랗게 — 초록불이 무엇을 확인한 것인지가 드러나게', {
  size: 12.5, fill: C.muted, anchor: 'start',
}));

const box = (x, y, w, h, name, lines, o = {}) => {
  p.push(card(x, y, w, h, { fill: o.fill ?? C.paper, stroke: o.stroke ?? C.line, strokeWidth: o.strokeWidth ?? 1 }));
  p.push(stack(x + w / 2, y + h / 2, name, lines, { titleFill: o.nameFill ?? C.ink, gap: 15, titleSize: 13 }));
};

// ── CI
const CI_Y = 108, BH = 78;
p.push(group(40, CI_Y - 26, 1100, 130, 'CI — Pull Request 마다. 합치기 전에 막는다'));
const ciW = 250, ciGap = 270;
box(60, CI_Y, ciW, BH, '규약 검사', ['모듈 경계 · 보안 기준선']);
box(60 + ciGap, CI_Y, ciW, BH, '전체 테스트', ['Java 21 · 브라우저 E2E 포함']);
box(60 + ciGap * 2, CI_Y, ciW, BH, '실제로 돌았는지', ['JUnit XML 의 skipped = 0'],
  { fill: C.redSoft, stroke: C.redLine, nameFill: C.red });
box(60 + ciGap * 3, CI_Y, 270, BH, 'SQL Server 잡', ['기종 전용 경로를 따로 돈다'],
  { fill: C.redSoft, stroke: C.redLine, nameFill: C.red });
[0, 1, 2].forEach((i) => p.push(elbowH([60 + ciW + i * ciGap, CI_Y + BH / 2], [60 + ciGap + i * ciGap, CI_Y + BH / 2])));

p.push(text(W - 40, CI_Y + BH + 22,
  '게이트를 못 받으면 테스트가 전부 건너뛰고도 초록불이 된다 — 그래서 돌았는지를 따로 단언한다',
  { size: 11, fill: C.red, anchor: 'end' }));

// ── Release
const RL_Y = 286;
p.push(group(40, RL_Y - 26, 1100, 130, 'Release — vX.Y.Z 태그를 push 하면'));
box(60, RL_Y, ciW, BH, '같은 게이트 재실행', ['규약 · 전체 테스트 · E2E'],
  { fill: C.redSoft, stroke: C.redLine, nameFill: C.red });
box(60 + ciGap, RL_Y, ciW, BH, 'QEMU + Buildx', ['arm64 는 에뮬레이션으로']);
box(60 + ciGap * 2, RL_Y, ciW, BH, 'GHCR 이미지', ['amd64 + arm64 · SemVer'],
  { fill: C.blueSoft, stroke: C.blueLine, nameFill: C.blue });
box(60 + ciGap * 3, RL_Y, 270, BH, 'GitHub Release', ['CHANGELOG 절을 그대로 싣는다'],
  { fill: C.blueSoft, stroke: C.blueLine, nameFill: C.blue });
[0, 1, 2].forEach((i) => p.push(elbowH([60 + ciW + i * ciGap, RL_Y + BH / 2], [60 + ciGap + i * ciGap, RL_Y + BH / 2])));

p.push(elbow([600, CI_Y + BH + 36], [600, RL_Y - 26], { arrow: true }));

// ── 런타임
const RT_Y = 448;
p.push(card(40, RT_Y, 540, 84, { fill: C.slate, stroke: C.line }));
p.push(stack(310, RT_Y + 42, '셀프호스트 런타임', [
  'Docker Compose — DBTower + 전용 메타 DB(PostgreSQL · Flyway)',
  '관리 대상 DB 는 띄우지 않는다. 로그인 후 콘솔에서 등록한다',
]));

p.push(card(620, RT_Y, 520, 84, { fill: C.paper, stroke: C.line }));
p.push(stack(880, RT_Y + 42, '관리 대상 프로비저닝', [
  'Kubernetes · Terraform · Ansible — 앱 배포와 분리한다',
  '플랫폼은 대상을 만들지 않고 등록만 받는다',
]));

p.push(elbow([310, RL_Y + BH + 26], [310, RT_Y]));
p.push(tag(310, RL_Y + BH + 26, '버전을 고정해 받는다'));

writeFileSync(new URL('./deployment-flow.svg', import.meta.url), svg(W, H, p.join('\n'), 'DBTower 배포 흐름'));
console.log('docs/diagrams/deployment-flow.svg');
