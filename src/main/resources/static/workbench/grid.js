// 결과 그리드 — 받은 행(최대 1000) 안에서 정렬·찾기·페이지 나눔. 서버를 다시 부르지 않는다.
// 선택 모드에서는 열 머리를 누르면 정렬 대신 그 열을 채팅에 붙인다.

import { esc } from "./api.js";

const PAGE_SIZE = 50;

function compare(a, b) {
  if (a === b) return 0;
  if (a === null || a === undefined) return 1;
  if (b === null || b === undefined) return -1;
  if (typeof a === "number" && typeof b === "number") return a - b;
  return String(a).localeCompare(String(b), "ko", { numeric: true });
}

export function renderGrid(container, view, { isPicking = () => false, onPickColumn = () => {} } = {}) {
  if (!view.columns.length) {
    container.innerHTML = '<div class="muted">결과 열이 없는 문장입니다.</div>';
    return;
  }
  const masked = new Set(view.maskedColumns || []);
  const state = { sort: null, dir: 1, filter: "", page: 0 };

  const draw = (keepFilterFocus) => {
    let rows = view.rows.map((r, i) => ({ r, i }));
    if (state.filter) {
      const f = state.filter.toLowerCase();
      rows = rows.filter(({ r }) => r.some((v) => v !== null && String(v).toLowerCase().includes(f)));
    }
    if (state.sort !== null) {
      const k = state.sort;
      rows.sort((a, b) => compare(a.r[k], b.r[k]) * state.dir);
    }
    const pages = Math.max(1, Math.ceil(rows.length / PAGE_SIZE));
    state.page = Math.min(state.page, pages - 1);
    const slice = rows.slice(state.page * PAGE_SIZE, (state.page + 1) * PAGE_SIZE);

    const head = view.columns.map((c, k) => {
      const arrow = state.sort === k ? (state.dir > 0 ? " ▲" : " ▼") : "";
      const badge = masked.has(c.name) ? ' <span class="mask-badge" title="마스킹 규칙으로 가려진 열">가림</span>' : "";
      return `<th data-sort="${k}" title="${esc(c.typeName || "")}">${esc(c.name)}${badge}${arrow}</th>`;
    }).join("");
    const body = slice.map(({ r, i }) =>
      `<tr><td class="rownum">${i + 1}</td>${r.map((v) => v === null
        ? '<td class="null">NULL</td>'
        : `<td title="${esc(v)}">${esc(v)}</td>`).join("")}</tr>`
    ).join("");

    container.innerHTML = `
      <div class="grid-bar">
        <input class="grid-filter" type="search" placeholder="결과 안에서 찾기" value="${esc(state.filter)}">
        <span class="muted">${rows.length}행${view.truncated ? " · 행 상한 도달(더 있음)" : ""}</span>
        <span class="wb-spacer"></span>
        <button class="btn btn-small" data-page="-1" ${state.page === 0 ? "disabled" : ""}>이전</button>
        <span class="muted">${state.page + 1} / ${pages}</span>
        <button class="btn btn-small" data-page="1" ${state.page >= pages - 1 ? "disabled" : ""}>다음</button>
      </div>
      <div class="grid-scroll"><table class="grid"><thead><tr><th class="rownum">#</th>${head}</tr></thead><tbody>${body}</tbody></table></div>`;

    const input = container.querySelector(".grid-filter");
    input.addEventListener("input", () => {
      state.filter = input.value;
      state.page = 0;
      draw(true);
    });
    if (keepFilterFocus) {
      input.focus();
      input.setSelectionRange(input.value.length, input.value.length);
    }
    container.querySelectorAll("th[data-sort]").forEach((th) => th.addEventListener("click", () => {
      const k = Number(th.dataset.sort);
      if (isPicking()) {
        onPickColumn(view.columns[k].name);
        return;
      }
      state.dir = state.sort === k ? -state.dir : 1;
      state.sort = k;
      draw(false);
    }));
    container.querySelectorAll("button[data-page]").forEach((b) => b.addEventListener("click", () => {
      state.page += Number(b.dataset.page);
      draw(false);
    }));
  };
  draw(false);
}
