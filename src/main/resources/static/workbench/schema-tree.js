// 스키마 트리 — 클릭은 이름을 편집기 커서에, 더블클릭은 미리보기 쿼리를, 선택 모드에서의 클릭은 채팅 칩을.
// 한 번 클릭과 더블클릭이 겹치지 않게, 클릭은 잠깐 기다렸다가 더블클릭이 아니면 실행한다.

import { esc } from "./api.js";

const CLICK_DELAY_MS = 220;

export function renderTree(container, schema, { filter, expanded, onInsert, onPreview, onToggle, isPicking, onPick, onDetail = () => {} }) {
  if (!schema) {
    container.innerHTML = '<div class="muted">스키마를 불러오지 못했습니다</div>';
    return;
  }
  const f = (filter || "").toLowerCase();
  const matchesColumn = (t) => t.columns.some((c) => c.name.toLowerCase().includes(f));
  const tables = schema.tables.filter((t) => !f || t.name.toLowerCase().includes(f) || matchesColumn(t));
  if (!tables.length) {
    container.innerHTML = `<div class="muted">${schema.tables.length
      ? "검색 결과가 없습니다"
      : "모니터 계정이 볼 수 있는 테이블이 없습니다(스키마 트리는 모니터 계정의 카탈로그 권한을 따릅니다)"}</div>`;
    return;
  }
  const cap = schema.truncated ? `<div class="wb-note">테이블이 많아 ${esc(schema.tableCap)}개까지만 보입니다</div>` : "";
  container.innerHTML = cap + `<ul class="tree">${tables.map((t) => {
    const open = expanded.has(t.name) || (f && matchesColumn(t));
    const columns = open
      ? `<ul class="tree-cols">${t.columns.map((c) =>
        `<li class="tree-col" data-insert="${esc(c.name)}" data-table="${esc(t.name)}" data-column="${esc(c.name)}" title="${esc(c.type)}${c.nullable ? "" : " NOT NULL"}"><span>${esc(c.name)}</span><span class="muted">${esc(c.type)}</span></li>`
      ).join("")}</ul>`
      : "";
    return `<li class="tree-table">
      <div class="tree-row">
        <button class="tree-caret" data-toggle="${esc(t.name)}" aria-label="열 펼치기">${open ? "▾" : "▸"}</button>
        <span class="tree-name" data-insert="${esc(t.name)}" data-preview="${esc(t.name)}" data-table="${esc(t.name)}" title="클릭: 이름 넣기 · 더블클릭: 미리보기 쿼리">${esc(t.name)}</span>
        <span class="tree-count muted">${t.columns.length}</span>
        <button class="tree-detail" data-detail="${esc(t.name)}" title="테이블 상세: 행 수·크기·인덱스·DDL">상세</button>
      </div>${columns}
    </li>`;
  }).join("")}</ul>`;

  let pending = null;
  container.onclick = (e) => {
    const toggle = e.target.closest("[data-toggle]");
    if (toggle) {
      onToggle(toggle.dataset.toggle);
      return;
    }
    const detail = e.target.closest("[data-detail]");
    if (detail) {
      onDetail(detail.dataset.detail);
      return;
    }
    const target = e.target.closest("[data-insert]");
    if (!target) return;
    if (isPicking()) {
      onPick(target.dataset.column
        ? { type: "column", value: `${target.dataset.table}.${target.dataset.column}` }
        : { type: "table", value: target.dataset.table });
      return;
    }
    clearTimeout(pending);
    pending = setTimeout(() => onInsert(target.dataset.insert), CLICK_DELAY_MS);
  };
  container.ondblclick = (e) => {
    const target = e.target.closest("[data-preview]");
    if (!target || isPicking()) return;
    clearTimeout(pending);
    onPreview(target.dataset.preview);
  };
}
