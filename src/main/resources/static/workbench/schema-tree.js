// 스키마 트리 — 클릭은 이름을 편집기 커서에, 더블클릭은 미리보기 쿼리를, 선택 모드에서의 클릭은 채팅 칩을.
// 한 번 클릭과 더블클릭이 겹치지 않게, 클릭은 잠깐 기다렸다가 더블클릭이 아니면 실행한다.
//
// 깊이(150절): 루트(데이터베이스·개수) -> 테이블 / 뷰 묶음 -> 열(기본키 표시)·인덱스. 전에는 테이블 -> 열 두 단계뿐이라
// 뷰가 테이블처럼 섞였고(PostgreSQL pg_stat_statements), 서버가 이미 받아 오던 인덱스가 트리에 보이지 않았다.

import { esc } from "./api.js";

const CLICK_DELAY_MS = 220;
// 뷰 묶음은 기본으로 접는다 — 확장 뷰(pg_stat_statements 등)가 사용자 테이블보다 앞에 늘어서지 않게
// 묶음 키는 테이블 이름과 겹치지 않게 NUL로 시작한다. 소스에는 이스케이프로 적는다 — 원시 NUL이 들어가면 git이 파일을 바이너리로 본다(154절)
// 테이블 이름과 겹치지 않는 키가 필요해 전에는 NUL(\u0000)을 앞에 붙였는데, 그 키를 data-toggle 속성(HTML)에 실으면
// 파서가 NUL을 U+FFFD로 바꿔 클릭한 키가 이 값과 영영 같지 않았다 — "뷰"를 눌러도 펼쳐지지 않았다(#40).
// 그래서 키는 속성에 싣지 않고, 묶음 머리는 data-toggle-group으로 따로 구분한다
const VIEWS_GROUP_KEY = "\u0000group:views";

export function renderTree(container, schema, { filter, expanded, onInsert, onPreview, onToggle, isPicking, onPick, onDetail = () => {}, error = null }) {
  if (!schema) {
    // 왜 못 불러왔는지까지 보인다 — 전에는 사유 없이 "불러오지 못했습니다"만 남아 화면이 멈춘 것처럼 보였다(B5)
    container.innerHTML = error
      ? `<div class="wb-msg error"><strong>스키마를 불러오지 못했습니다</strong><p>${esc(error)}</p></div>`
      : '<div class="muted">스키마를 불러오지 못했습니다</div>';
    return;
  }
  const f = (filter || "").toLowerCase();
  const hit = (s) => String(s).toLowerCase().includes(f);
  const matchesInside = (t) => t.columns.some((c) => hit(c.name)) || (t.indexes || []).some((i) => hit(i.name));
  const visible = schema.tables.filter((t) => !f || hit(t.name) || matchesInside(t));
  if (!visible.length) {
    container.innerHTML = `<div class="muted">${schema.tables.length
      ? "검색 결과가 없습니다"
      : "모니터 계정이 볼 수 있는 테이블이 없습니다(스키마 트리는 모니터 계정의 카탈로그 권한을 따릅니다)"}</div>`;
    return;
  }
  const isView = (t) => t.kind === "VIEW";
  const tableCount = schema.tables.filter((t) => !isView(t)).length;
  const viewCount = schema.tables.length - tableCount;
  const cap = schema.truncated ? `<div class="wb-note">테이블이 많아 ${esc(schema.tableCap)}개까지만 보입니다</div>` : "";
  const root = `<div class="tree-root" title="${esc(schema.type)} · ${esc(schema.database)}">
    <span class="tree-root-name">${esc(schema.database)}</span>
    <span class="tree-root-meta">테이블 ${tableCount}${viewCount ? ` · 뷰 ${viewCount}` : ""}</span></div>`;

  const item = (t) => {
    const open = expanded.has(t.name) || (f && matchesInside(t));
    const pk = new Set(t.primaryKey || []);
    const columns = open ? t.columns.map((c) => {
      const key = pk.has(c.name) ? '<span class="tree-pk" title="기본키">PK</span>' : "";
      return `<li class="tree-col${pk.has(c.name) ? " is-pk" : ""}" data-insert="${esc(c.name)}" data-table="${esc(t.name)}" data-column="${esc(c.name)}" title="${esc(c.type)}${c.nullable ? "" : " NOT NULL"}"><span>${esc(c.name)}${key}</span><span class="muted">${esc(c.type)}</span></li>`;
    }).join("") : "";
    const indexes = open && (t.indexes || []).length ? `<li class="tree-index-head muted">인덱스 ${t.indexes.length}</li>` + t.indexes.map((i) => {
      const isPk = (t.primaryKey || []).length && i.columns.join(",") === t.primaryKey.join(",");
      const flags = [i.unique ? "UNIQUE" : "", isPk ? "PK" : ""].filter(Boolean).join(" · ");
      return `<li class="tree-index" data-insert="${esc(i.name)}" data-index="1" title="${esc(i.name)} (${esc(i.columns.join(", "))})"><span class="tree-index-name">${esc(i.name)}</span><span class="muted">${esc(i.columns.join(", "))}${flags ? ` · ${esc(flags)}` : ""}</span></li>`;
    }).join("") : "";
    const inner = open && (columns || indexes) ? `<ul class="tree-cols">${columns}${indexes}</ul>` : "";
    // 테이블 상세(행 수·크기·DDL)는 테이블만 된다 — 뷰에 버튼을 두면 "테이블을 찾을 수 없습니다"를 받는다
    const detail = isView(t) ? "" : `<button class="tree-detail" data-detail="${esc(t.name)}" title="테이블 상세: 행 수·크기·인덱스·DDL">상세</button>`;
    return `<li class="tree-table${isView(t) ? " is-view" : ""}">
      <div class="tree-row">
        <button class="tree-caret" data-toggle="${esc(t.name)}" aria-label="열 펼치기" aria-expanded="${open}">${open ? "▾" : "▸"}</button>
        <span class="tree-name${isView(t) ? " is-view" : ""}" data-insert="${esc(t.name)}" data-preview="${esc(t.name)}" data-table="${esc(t.name)}" title="${isView(t) ? "뷰 · " : ""}클릭: 이름 넣기 · 더블클릭: 미리보기 쿼리">${esc(t.name)}</span>
        <span class="tree-count muted">${t.columns.length || ""}</span>
        ${detail}
      </div>${inner}
    </li>`;
  };

  const tables = visible.filter((t) => !isView(t));
  const views = visible.filter(isView);
  const viewsOpen = expanded.has(VIEWS_GROUP_KEY) || Boolean(f);
  const tablesGroup = tables.length ? `<li class="tree-group">
      <div class="tree-group-head tree-group-static">테이블 <span class="wb-count">${tables.length}</span></div>
      <ul class="tree">${tables.map(item).join("")}</ul></li>` : "";
  const viewsGroup = views.length ? `<li class="tree-group">
      <button class="tree-group-head" data-toggle-group="views" aria-expanded="${viewsOpen}">
        <span class="wb-chevron" aria-hidden="true"></span>뷰 <span class="wb-count">${views.length}</span></button>
      ${viewsOpen ? `<ul class="tree">${views.map(item).join("")}</ul>` : ""}</li>` : "";
  container.innerHTML = cap + root + `<ul class="tree tree-groups">${tablesGroup}${viewsGroup}</ul>`;

  let pending = null;
  container.onclick = (e) => {
    if (e.target.closest("[data-toggle-group]")) {
      onToggle(VIEWS_GROUP_KEY);
      return;
    }
    const toggle = e.target.closest("[data-toggle]");
    if (toggle) {
      onToggle(toggle.dataset.toggle);
      return;
    }
    const detailBtn = e.target.closest("[data-detail]");
    if (detailBtn) {
      onDetail(detailBtn.dataset.detail);
      return;
    }
    const target = e.target.closest("[data-insert]");
    if (!target) return;
    if (isPicking()) {
      // 칩은 테이블·열만 — 인덱스 이름은 질문 재료가 아니다
      if (target.dataset.index) return;
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
