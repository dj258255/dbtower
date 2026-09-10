// 전후 비교 렌더링 — 행 차이(변경 전후 사본, 인스턴스 간 결과), 구조 변화, 검증 조회 실행계획, 워크로드 비교.
// 값은 서버가 마스킹한 뒤에 온다. 바뀌었는지 판정도 서버가 원래 값으로 해서 보낸다 — 여기서는 다시 판정하지 않는다.

import { esc } from "./api.js";

const time = (t) => (t ? String(t).replace("T", " ").slice(0, 16) : "");
const cell = (v) => (v === null || v === undefined ? '<span class="null">NULL</span>' : esc(v));
const micros = (v) => (v === null || v === undefined ? "-" : v < 1000 ? `${v}µs` : `${(v / 1000).toFixed(v < 10000 ? 2 : 1)}ms`);
const pct = (v) => (v === null || v === undefined ? "-" : `${v > 0 ? "+" : ""}${Number(v).toFixed(1)}%`);
const num = (v) => (v === null || v === undefined ? "-" : Number(v).toLocaleString("ko-KR", { maximumFractionDigits: 2 }));

export function renderDiff(diff, { leftLabel = "전", rightLabel = "후", addedLabel = "추가", removedLabel = "삭제", maskedColumns = [] } = {}) {
  const masked = new Set((maskedColumns || []).map((c) => c.toLowerCase()));
  const keys = new Set((diff.keyColumns || []).map((c) => c.toLowerCase()));
  const identical = diff.added + diff.removed + diff.changed === 0;
  const summary = `<div class="df-summary">
      ${identical ? '<span class="badge read">차이 없음</span>' : ""}
      <span>바뀜 <b>${num(diff.changed)}</b></span>
      <span>${esc(addedLabel)} <b>${num(diff.added)}</b></span>
      <span>${esc(removedLabel)} <b>${num(diff.removed)}</b></span>
      <span class="muted">같음 ${num(diff.unchanged)} · ${esc(leftLabel)} ${num(diff.leftRows)}행 / ${esc(rightLabel)} ${num(diff.rightRows)}행 ·
        ${diff.keyColumns && diff.keyColumns.length ? "키 " + esc(diff.keyColumns.join(", ")) : "키 없음(행 전체 비교)"}</span>
    </div>`;
  const maskedChanged = diff.changes.some((ch) => ch.cells.some((c) => c.changed && masked.has(c.column.toLowerCase())));
  const notes = (diff.notes || []).map((n) => `<div class="hint">${esc(n)}</div>`).join("")
    + (maskedChanged ? '<div class="hint">가린 열은 값이 달라도 같아 보일 수 있습니다. 바뀜 판정은 서버가 원래 값으로 했습니다.</div>' : "")
    + (diff.truncated ? `<div class="hint">차이 행이 많아 앞 ${diff.changes.length}행만 보여줍니다.</div>` : "");
  if (!diff.changes.length) return `<div class="df">${summary}${notes}</div>`;

  const head = diff.columns.map((c) => `<th class="${keys.has(c.toLowerCase()) ? "df-key" : ""}">${esc(c)}${masked.has(c.toLowerCase()) ? ' <span class="mask-badge">가림</span>' : ""}</th>`).join("");
  const label = { CHANGED: "바뀜", ADDED: addedLabel, REMOVED: removedLabel };
  const body = diff.changes.map((ch) => {
    const tds = ch.cells.map((c) => {
      if (ch.type === "ADDED") return `<td>${cell(c.right)}</td>`;
      if (ch.type === "REMOVED") return `<td>${cell(c.left)}</td>`;
      return c.changed
        ? `<td class="df-cell"><del>${cell(c.left)}</del><span class="df-arrow">→</span><ins>${cell(c.right)}</ins></td>`
        : `<td class="muted">${cell(c.left)}</td>`;
    }).join("");
    return `<tr class="df-${esc(ch.type.toLowerCase())}"><td><span class="df-type">${esc(label[ch.type] || ch.type)}</span></td>${tds}</tr>`;
  }).join("");
  return `<div class="df">${summary}${notes}
    <div class="grid-scroll"><table class="grid df-table"><thead><tr><th>구분</th>${head}</tr></thead><tbody>${body}</tbody></table></div></div>`;
}

export function renderSchemaDiff(sd) {
  if (sd.identical) {
    return '<div class="df-schema"><div class="df-title">구조 변화</div><div class="muted">구조 차이가 없습니다(모니터 계정이 보는 범위 기준).</div></div>';
  }
  const lines = [];
  (sd.addedTables || []).forEach((t) => lines.push(["add", `테이블 ${t.name} 생김 (열 ${(t.columns || []).length})`]));
  (sd.removedTables || []).forEach((t) => lines.push(["remove", `테이블 ${t.name} 사라짐`]));
  (sd.changedTables || []).forEach((t) => {
    (t.addedColumns || []).forEach((c) => lines.push(["add", `${t.table}.${c.name} 열 생김 (${c.type}${c.nullable ? "" : ", NOT NULL"})`]));
    (t.removedColumns || []).forEach((c) => lines.push(["remove", `${t.table}.${c.name} 열 사라짐`]));
    (t.changedColumns || []).forEach((c) => lines.push(["change", `${t.table}.${c.name} ${c.leftType} → ${c.rightType}`
      + (c.leftNullable !== c.rightNullable ? ` (NULL 허용 ${c.leftNullable} → ${c.rightNullable})` : "")]));
    (t.addedIndexes || []).forEach((i) => lines.push(["add", `${t.table} 인덱스 ${i.name} 생김 (${(i.columns || []).join(", ")}${i.unique ? ", UNIQUE" : ""})`]));
    (t.removedIndexes || []).forEach((i) => lines.push(["remove", `${t.table} 인덱스 ${i.name} 사라짐`]));
    (t.changedIndexes || []).forEach((i) => lines.push(["change", `${t.table} 인덱스 ${i.name} 구성 바뀜`]));
  });
  return `<div class="df-schema"><div class="df-title">구조 변화</div>
    ${sd.warning ? `<div class="hint">${esc(sd.warning)}</div>` : ""}
    <ul>${lines.map(([kind, text]) => `<li class="sd-${kind}">${esc(text)}</li>`).join("")}</ul></div>`;
}

export function renderProbe(p) {
  const badge = p.beforePlan && p.afterPlan
    ? (p.planChanged ? '<span class="badge change">실행계획 바뀜</span>' : '<span class="badge read">실행계획 같음</span>') : "";
  const runs = Math.max((p.beforeMicros || []).length, (p.afterMicros || []).length);
  return `<div class="df-probe"><div class="df-title">검증 조회 전후 ${badge}</div>
    <div class="muted">응답시간 중앙값 ${micros(p.beforeMedianMicros)} → ${micros(p.afterMedianMicros)} · 같은 트랜잭션 안에서 각 ${runs}회 · 캐시 영향이 있어 방향만 참고</div>
    <div class="probe-cols">
      <div><div class="wb-label">전</div><pre>${esc(p.beforePlan || p.beforeError || "")}</pre></div>
      <div><div class="wb-label">후</div><pre>${esc(p.afterPlan || p.afterError || "")}</pre></div>
    </div></div>`;
}

export function renderWorkload(w) {
  const r = w.result;
  if (!r) return `<div class="df-workload"><div class="hint">${esc(w.note || "비교할 스냅샷이 없습니다.")}</div></div>`;
  const queries = [...(r.queries || [])]
    .filter((q) => q.latencyChangePct !== null || q.newQuery)
    .sort((a, b) => Math.abs(b.latencyChangePct ?? Infinity) - Math.abs(a.latencyChangePct ?? Infinity))
    .slice(0, 8);
  const table = queries.length
    ? `<table class="history"><tbody>${queries.map((q) => `<tr>
        <td><code>${esc(String(q.queryText || q.queryId).slice(0, 140))}</code></td>
        <td class="muted">${q.newQuery ? "새 쿼리" : `${num(q.baseAvgMs)}ms → ${num(q.targetAvgMs)}ms`}</td>
        <td>${q.newQuery ? "" : esc(pct(q.latencyChangePct))}</td></tr>`).join("")}</tbody></table>`
    : '<div class="muted">두 구간 모두에 잡힌 쿼리 스냅샷이 없습니다.</div>';
  return `<div class="df-workload">
    <div class="muted">전 ${esc(time(w.baseFrom))} ~ ${esc(time(w.baseTo))} / 후 ${esc(time(w.targetFrom))} ~ ${esc(time(w.targetTo))}</div>
    ${w.note ? `<div class="hint">${esc(w.note)}</div>` : ""}
    <div class="df-summary">
      <span>평균 지연 ${num(r.base.avgLatencyMs)}ms → ${num(r.target.avgLatencyMs)}ms (${esc(pct(r.avgLatencyChangePct))})</span>
      <span>호출 ${num(r.base.totalCalls)} → ${num(r.target.totalCalls)}</span>
      <span>스캔 행 ${num(r.base.totalRowsExamined)} → ${num(r.target.totalRowsExamined)}</span>
      <span>새 쿼리 ${num(r.newQueryCount)}</span>
    </div>${table}</div>`;
}
