// 기술 블로그에서 보는 모양의 다이어그램을 만드는 최소 도구.
//
// 왜 mermaid 를 버렸나(실측): mermaid 는 `htmlLabels: true` 가 기본이라 글자를 `<foreignObject>` 에 넣는다.
// 브라우저에서 mermaid 가 직접 그릴 때는 보이지만, SVG 파일로 뽑아 GitHub 나 PDF 에 넣으면
// **글자가 통째로 사라진다** — GitHub 는 SVG 를 sanitize 하며 foreignObject 를 지운다.
// 이 저장소의 네 그림이 전부 그랬다(`<text>` 0개, foreignObject 54~138개).
// 여기서는 글자를 평범한 `<text>` 로만 쓴다.
//
// 그리고 자동 배치를 쓰지 않는다. mermaid 가 ERD 를 1500x3321 로 밀어내 화면에 담기지도 않았다.
// 자리는 직접 잡는다 — 그림의 목적이 "한눈에"라면 배치가 곧 내용이다.

// 글꼴 이름에 큰따옴표를 쓰면 SVG 속성값(큰따옴표로 감싼)이 그 자리에서 끊긴다 — 작은따옴표로 쓴다
const FONT = "Pretendard, 'Apple SD Gothic Neo', -apple-system, 'Segoe UI', sans-serif";
const MONO = "'SF Mono', ui-monospace, 'Cascadia Code', Menlo, monospace";

export const C = {
  ink: '#111827',        // 제목
  body: '#374151',       // 본문
  muted: '#6b7280',      // 보조
  line: '#d1d5db',       // 테두리
  edge: '#9ca3af',       // 연결선
  paper: '#ffffff',
  canvas: '#fafafa',     // 묶음 배경
  blue: '#0a6aa8',
  blueSoft: '#eff6ff',
  blueLine: '#bfdbfe',
  red: '#b42335',
  redSoft: '#fef2f2',
  redLine: '#fecaca',
  slate: '#f3f4f6',
};

const esc = (s) => String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');

/** 모서리 둥근 면 하나. 테두리는 얇게, 채움은 옅게 — 선이 굵으면 격자처럼 읽힌다. */
export function card(x, y, w, h, {
  fill = C.paper, stroke = C.line, r = 10, strokeWidth = 1, dash = null, shadow = false,
} = {}) {
  const sh = shadow
    ? `<rect x="${x}" y="${y + 1}" width="${w}" height="${h}" rx="${r}" fill="#0f172a" opacity="0.05"/>`
    : '';
  return `${sh}<rect x="${x}" y="${y}" width="${w}" height="${h}" rx="${r}" fill="${fill}"`
    + ` stroke="${stroke}" stroke-width="${strokeWidth}"`
    + (dash ? ` stroke-dasharray="${dash}"` : '') + '/>';
}

export function text(x, y, s, {
  size = 13, weight = 400, fill = C.body, anchor = 'middle', mono = false, opacity = 1,
} = {}) {
  return `<text x="${x}" y="${y}" font-family="${mono ? MONO : FONT}" font-size="${size}"`
    + ` font-weight="${weight}" fill="${fill}" text-anchor="${anchor}"`
    + (opacity !== 1 ? ` opacity="${opacity}"` : '')
    + ` dominant-baseline="middle">${esc(s)}</text>`;
}

/** 면 안의 제목 + 설명 줄. 줄 수에 맞춰 세로 가운데 정렬한다. */
export function stack(cx, cy, title, lines = [], {
  titleSize = 14, lineSize = 11.5, titleFill = C.ink, lineFill = C.muted, gap = 16, mono = false,
} = {}) {
  const total = lines.length * gap;
  let y = cy - total / 2;
  const out = [text(cx, y, title, { size: titleSize, weight: 600, fill: titleFill, mono })];
  for (const l of lines) {
    y += gap;
    out.push(text(cx, y, l, { size: lineSize, fill: lineFill }));
  }
  return out.join('\n');
}

/** 직각으로 꺾이는 연결선 — 곡선이 겹치면 어디서 어디로 가는지 읽을 수 없다. */
export function elbow(from, to, { stroke = C.edge, width = 1.4, arrow = true, dash = null, mid = null } = {}) {
  const [x1, y1] = from;
  const [x2, y2] = to;
  let d;
  if (Math.abs(y1 - y2) < 1) {
    d = `M ${x1} ${y1} H ${x2}`;
  } else if (Math.abs(x1 - x2) < 1) {
    d = `M ${x1} ${y1} V ${y2}`;
  } else {
    const m = mid ?? (y1 + y2) / 2;     // 세로로 먼저, 가로로, 다시 세로로
    d = `M ${x1} ${y1} V ${m} H ${x2} V ${y2}`;
  }
  return `<path d="${d}" fill="none" stroke="${stroke}" stroke-width="${width}"`
    + (dash ? ` stroke-dasharray="${dash}"` : '')
    + ` stroke-linecap="round" stroke-linejoin="round"`
    + (arrow ? ' marker-end="url(#arrow)"' : '') + '/>';
}

/** 가로로만 가는 연결선(세로 먼저 꺾지 않는다). */
export function elbowH(from, to, opts = {}) {
  const [x1, y1] = from;
  const [x2, y2] = to;
  const m = opts.mid ?? (x1 + x2) / 2;
  const d = Math.abs(y1 - y2) < 1 ? `M ${x1} ${y1} H ${x2}` : `M ${x1} ${y1} H ${m} V ${y2} H ${x2}`;
  return `<path d="${d}" fill="none" stroke="${opts.stroke ?? C.edge}" stroke-width="${opts.width ?? 1.4}"`
    + (opts.dash ? ` stroke-dasharray="${opts.dash}"` : '')
    + ` stroke-linecap="round" stroke-linejoin="round"`
    + ((opts.arrow ?? true) ? ' marker-end="url(#arrow)"' : '') + '/>';
}

/** 연결선 위에 놓는 작은 꼬리표 — 선을 가리지 않게 흰 바탕을 깐다. */
export function tag(x, y, s, { size = 10.5, fill = C.muted } = {}) {
  const w = s.length * size * 0.62 + 10;
  return `<rect x="${x - w / 2}" y="${y - 9}" width="${w}" height="18" rx="4" fill="${C.paper}"/>`
    + text(x, y, s, { size, fill });
}

/** 묶음 — 배경을 아주 옅게 깔고 제목을 왼쪽 위에. 테두리는 점선으로 약하게. */
export function group(x, y, w, h, title, { fill = C.canvas, stroke = C.line } = {}) {
  return card(x, y, w, h, { fill, stroke, r: 12, dash: '4 4' })
    + '\n' + text(x + 16, y + 18, title, { size: 12, weight: 600, fill: C.muted, anchor: 'start' });
}

export function svg(width, height, body, title) {
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${width} ${height}" width="${width}" height="${height}" role="img" aria-label="${esc(title)}">
<title>${esc(title)}</title>
<defs>
  <marker id="arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
    <path d="M 0 1 L 9 5 L 0 9 z" fill="${C.edge}"/>
  </marker>
</defs>
<rect width="${width}" height="${height}" fill="${C.paper}"/>
${body}
</svg>
`;
}
