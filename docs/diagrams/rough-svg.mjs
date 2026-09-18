// rough.js 로 손그림 느낌의 SVG 를 만드는 최소 도구.
//
// 왜 DOM 없이 generator 만 쓰나: roughjs 의 SVG 렌더러는 document 를 요구해 jsdom 이 따라온다.
// generator().toPaths() 는 경로 문자열만 돌려주므로 의존이 roughjs 하나로 끝난다.
//
// 왜 mermaid 로 안 그리나: mermaid 도 look: handDrawn 으로 같은 rough.js 를 쓴다. 다른 것은 배치다.
// mermaid 는 노드를 자동 배치해 개수가 늘수록 커지는데, 이 그림들의 목적은 "핵심만"이라
// 자리를 직접 잡아야 했다(docs/architecture-detail.svg 는 노드 26개에 1MB 다).
import rough from 'roughjs';

const gen = rough.generator();

const FONT = 'Pretendard, Apple SD Gothic Neo, -apple-system, sans-serif';

export const C = {
  ink: '#172033',
  line: '#52606d',
  muted: '#52697d',
  primary: '#0a6aa8',
  danger: '#b42335',
  paper: '#ffffff',
  tintBlue: '#eef7fd',
  tintGray: '#f2f4f6',
  tintRed: '#fdeff0',
};

function esc(s) {
  return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

function paths(drawable) {
  return gen.toPaths(drawable).map((p) => {
    const fill = p.fill && p.fill !== 'none' ? p.fill : 'none';
    return `<path d="${p.d}" stroke="${p.stroke}" stroke-width="${p.strokeWidth}" fill="${fill}"`
      + (p.strokeLineDash ? ` stroke-dasharray="${p.strokeLineDash.join(' ')}"` : '')
      + ' stroke-linecap="round" stroke-linejoin="round"/>';
  }).join('\n');
}

/** 상자 하나. seed 를 고정해 다시 만들어도 같은 그림이 나온다(diff 가 튀지 않게). */
export function box(x, y, w, h, { fill = C.paper, stroke = C.line, seed = 1, strokeWidth = 1.6 } = {}) {
  return paths(gen.rectangle(x, y, w, h, {
    roughness: 1.1, bowing: 1.4, seed, stroke, strokeWidth,
    fill, fillStyle: 'solid',
  }));
}

/** 묶음 테두리 — 점선으로 두어 상자와 구분한다. */
export function group(x, y, w, h, { seed = 2, stroke = '#9aa5b1' } = {}) {
  return paths(gen.rectangle(x, y, w, h, {
    roughness: 1.3, bowing: 1.6, seed, stroke, strokeWidth: 1.4,
    strokeLineDash: [8, 6], fill: 'none',
  }));
}

export function line(x1, y1, x2, y2, { seed = 3, stroke = C.line, strokeWidth = 1.6, dash = null } = {}) {
  return paths(gen.line(x1, y1, x2, y2, {
    roughness: 1.1, bowing: 1.2, seed, stroke, strokeWidth,
    strokeLineDash: dash,
  }));
}

/** 화살표 — 선 + 머리 두 획. 각도를 직접 계산해 어느 방향이든 선다. */
export function arrow(x1, y1, x2, y2, opts = {}) {
  const { seed = 4, stroke = C.line, head = 9, dash = null } = opts;
  const a = Math.atan2(y2 - y1, x2 - x1);
  const w = 0.42;
  return [
    line(x1, y1, x2, y2, { seed, stroke, dash }),
    line(x2, y2, x2 - head * Math.cos(a - w), y2 - head * Math.sin(a - w), { seed: seed + 1, stroke }),
    line(x2, y2, x2 - head * Math.cos(a + w), y2 - head * Math.sin(a + w), { seed: seed + 2, stroke }),
  ].join('\n');
}

export function text(x, y, s, { size = 13, weight = 400, fill = C.ink, anchor = 'middle' } = {}) {
  return `<text x="${x}" y="${y}" font-family="${FONT}" font-size="${size}" font-weight="${weight}"`
    + ` fill="${fill}" text-anchor="${anchor}">${esc(s)}</text>`;
}

/** 상자 안에 제목 한 줄 + 설명 여러 줄. 줄 수에 맞춰 세로 가운데로 맞춘다. */
export function label(cx, cy, title, lines = [], { titleSize = 14, lineSize = 11.5, fill = C.ink } = {}) {
  const gap = 15;
  const total = (lines.length + 1) * gap;
  let y = cy - total / 2 + gap - 3;
  const out = [text(cx, y, title, { size: titleSize, weight: 600, fill })];
  for (const l of lines) {
    y += gap;
    out.push(text(cx, y, l, { size: lineSize, fill: C.muted }));
  }
  return out.join('\n');
}

export function svg(width, height, body, title) {
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${width} ${height}" width="${width}" height="${height}" role="img" aria-label="${esc(title)}">
<title>${esc(title)}</title>
<rect width="${width}" height="${height}" fill="${C.paper}"/>
${body}
</svg>
`;
}
