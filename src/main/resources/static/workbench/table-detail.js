// 테이블 상세 한 벌(VERIFICATION 151절) — 워크벤치 "테이블 상세" 탭과 관제 "관련 테이블 구조" 펼침이 같은 렌더러를 쓴다.
// 미확보 값(-1·null)은 "미확보"로 적는다. 추정치를 0으로 바꿔 보이지 않는다.

import { esc } from "./api.js";
import { highlight } from "./editor.js";

const DDL_SOURCE = { NATIVE: "엔진이 준 원문", RECONSTRUCTED: "카탈로그로 재구성한 근사", UNSUPPORTED: "미지원" };

export function bytes(n) {
  if (n === null || n === undefined || n < 0) return "미확보";
  const units = ["B", "KB", "MB", "GB", "TB"];
  let v = n;
  let u = 0;
  while (v >= 1024 && u < units.length - 1) { v /= 1024; u++; }
  return `${v.toFixed(u ? 1 : 0)} ${units[u]}`;
}

const count = (n) => (n === null || n === undefined || n < 0 ? "미확보" : Number(n).toLocaleString("ko-KR"));
const lower = (s) => String(s ?? "").toLowerCase();
const colList = (cols) => esc((cols || []).join(", "));
const rule = (v) => (v ? esc(v) : '<span class="muted">-</span>');

// 외래키 열로 시작하는 인덱스가 없으면, 참조되는 행을 지우거나 키를 바꿀 때마다 엔진이 이 테이블을 인덱스 없이 찾는다.
// 복합 키는 앞쪽 열의 순서가 달라도 같은 열 묶음이면 찾기에 쓰인다
function hasLeadingIndex(fk, indexes) {
  const want = new Set(fk.columns.map(lower));
  return indexes.some((i) => {
    const head = (i.columns || []).slice(0, want.size).map(lower);
    return head.length === want.size && head.every((c) => want.has(c));
  });
}

/**
 * @param d            /table-detail 응답
 * @param columns      열 목록 [{name, type, nullable}] — 스키마 트리나 참조 구조에서 받는다. 없으면 열 절을 뺀다
 * @param canOpen      (table) => boolean — 참조 테이블을 눌러 그 상세로 넘어갈 수 있는지. 없으면 글자로만 적는다
 * @param pickColumns  열 이름을 눌러 채팅에 붙일 수 있게(워크벤치)
 * @param actions      머리 오른쪽에 둘 버튼 HTML(호출자가 만든다)
 */
export function renderTableDetail(d, { columns = null, canOpen = null, pickColumns = false, actions = "", title = null } = {}) {
  const pk = (d.primaryKey || []).map(lower);
  const indexes = d.indexes || [];
  const outgoing = d.foreignKeys || [];
  const incoming = d.referencedBy || [];
  const fkOf = new Map();
  for (const fk of outgoing) {
    fk.columns.forEach((c, k) => { if (!fkOf.has(lower(c))) fkOf.set(lower(c), { fk, ref: fk.refColumns[k] }); });
  }
  const tableRef = (t) => (canOpen && canOpen(t)
    ? `<button class="td-link" data-open-table="${esc(t)}" title="${esc(t)} 상세로">${esc(t)}</button>`
    : `<span class="td-ref">${esc(t)}</span>`);

  const pills = [
    ...(d.engine ? [["엔진", d.engine]] : []),
    ["행 수(추정)", count(d.rowCount)], ["데이터", bytes(d.dataBytes)], ["인덱스", bytes(d.indexBytes)],
    ["평균 행", bytes(d.avgRowBytes)], ["생성", d.createdAt || "미확보"],
  ].map(([k, v]) => `<div class="td-pill"><span>${esc(k)}</span><b>${esc(v)}</b></div>`).join("");

  let columnsBlock = "";
  if (columns) {
    const rows = columns.map((c) => {
      const key = lower(c.name);
      const link = fkOf.get(key);
      const name = pickColumns
        ? `<button class="td-col" data-col="${esc(c.name)}" title="채팅에 붙이기">${esc(c.name)}</button>`
        : `<span>${esc(c.name)}</span>`;
      const badges = (pk.includes(key) ? '<span class="key-badge pk" title="기본키">PK</span>' : "")
        + (link ? `<span class="key-badge fk" title="외래키 ${esc(link.fk.name)}">FK</span>` : "");
      return `<tr><td class="td-name">${name}${badges}</td><td class="td-type">${esc(c.type)}</td>
        <td class="td-null">${c.nullable ? '<span class="muted">NULL</span>' : "NOT NULL"}</td>
        ${outgoing.length ? `<td class="td-to">${link ? `${tableRef(link.fk.refTable)}<span class="muted">.${esc(link.ref)}</span>` : ""}</td>` : ""}</tr>`;
    }).join("");
    columnsBlock = `<section class="td-section"><h4>열 <span class="muted">${columns.length}</span></h4>
      <div class="td-scroll"><table class="td-table"><thead><tr><th>이름</th><th>타입</th><th>NULL</th>${outgoing.length ? "<th>참조</th>" : ""}</tr></thead>
      <tbody>${rows}</tbody></table></div></section>`;
  }

  const isPkIndex = (i) => pk.length > 0 && i.unique && (i.columns || []).length === pk.length
    && i.columns.every((c, k) => lower(c) === pk[k]);
  const indexRows = indexes.map((i) => `<tr><td class="td-name">${esc(i.name)}${isPkIndex(i) ? '<span class="key-badge pk">PK</span>' : ""}</td>
      <td>${colList(i.columns)}</td><td>${i.unique ? "UNIQUE" : '<span class="muted">-</span>'}</td>
      <td class="muted">${esc(i.type || "")}</td>
      <td class="td-num">${i.cardinality === null || i.cardinality === undefined ? '<span class="muted">미확보</span>' : esc(count(i.cardinality))}</td></tr>`).join("");
  const indexBlock = `<section class="td-section"><h4>인덱스 <span class="muted">${indexes.length}</span></h4>
    ${indexes.length
      ? `<div class="td-scroll"><table class="td-table"><thead><tr><th>이름</th><th>열</th><th>유일</th><th>타입</th><th>카디널리티</th></tr></thead><tbody>${indexRows}</tbody></table></div>`
      : '<div class="muted">인덱스가 없거나 확보하지 못했습니다.</div>'}</section>`;

  const outRows = outgoing.map((fk) => `<tr><td class="td-name">${esc(fk.name)}</td>
      <td>${colList(fk.columns)} → ${tableRef(fk.refTable)} (${colList(fk.refColumns)})${hasLeadingIndex(fk, indexes) ? ""
        : ' <span class="key-warn" title="외래키 열로 시작하는 인덱스가 없어, 참조되는 행을 지우거나 키를 바꿀 때 이 테이블을 인덱스 없이 찾습니다">인덱스 없음</span>'}</td>
      <td class="td-rule">${rule(fk.onDelete)}</td><td class="td-rule">${rule(fk.onUpdate)}</td></tr>`).join("");
  const inRows = incoming.map((fk) => `<tr><td class="td-name">${esc(fk.name)}</td>
      <td>${tableRef(fk.table)} (${colList(fk.columns)}) → ${colList(fk.refColumns)}</td>
      <td class="td-rule">${rule(fk.onDelete)}</td><td class="td-rule">${rule(fk.onUpdate)}</td></tr>`).join("");
  const keyHead = "<thead><tr><th>제약</th><th>열 → 참조</th><th>ON DELETE</th><th>ON UPDATE</th></tr></thead>";
  const keysBlock = `<section class="td-section"><h4>외래키</h4>
    ${outgoing.length ? `<div class="td-sub">이 테이블이 가리키는 것 <span class="muted">${outgoing.length}</span></div>
      <div class="td-scroll"><table class="td-table">${keyHead}<tbody>${outRows}</tbody></table></div>` : ""}
    ${incoming.length ? `<div class="td-sub">이 테이블을 가리키는 것 <span class="muted">${incoming.length}</span></div>
      <div class="td-scroll"><table class="td-table">${keyHead}<tbody>${inRows}</tbody></table></div>
      <div class="td-hint">이 테이블의 행을 지우거나 키를 바꾸면 ON DELETE·ON UPDATE대로 막히거나(NO ACTION·RESTRICT), 함께 지워지거나(CASCADE), 비워집니다(SET NULL).</div>` : ""}
    ${outgoing.length || incoming.length ? "" : '<div class="muted">외래키가 없습니다.</div>'}</section>`;

  const ddlBlock = `<details class="td-ddl-box"><summary>DDL <span class="muted">${esc(DDL_SOURCE[d.ddlSource] || d.ddlSource || "")}</span></summary>
    ${d.ddl ? `<pre class="ai-sql td-ddl"><code>${highlight(d.ddl)}</code></pre>` : '<div class="muted">DDL을 확보하지 못했습니다.</div>'}</details>`;

  return `<div class="td-view">
    <div class="td-head"><strong class="td-title">${esc(title || d.table)}</strong>
      ${pk.length ? `<span class="td-pk muted">기본키 (${colList(d.primaryKey)})</span>` : ""}
      <span class="wb-spacer"></span>${actions}</div>
    <div class="td-pills">${pills}</div>
    <div class="td-grid${columnsBlock ? "" : " single"}">${columnsBlock ? `<div>${columnsBlock}</div>` : ""}<div>${indexBlock}${keysBlock}</div></div>
    ${ddlBlock}
    ${d.note ? `<div class="td-note">${esc(d.note)}</div>` : ""}
  </div>`;
}
