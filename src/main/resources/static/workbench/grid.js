// 결과 그리드 — 받은 행(최대 1000) 안에서 정렬·찾기·열별 거르기·페이지 나눔. 서버를 다시 부르지 않는다.
// 선택 모드에서는 열 머리를 누르면 정렬 대신 그 열을 채팅에 붙인다.
// 151절: 열 머리 끝을 끌어 너비 조절(두 번 누르면 원래대로), 행을 누르면 세로 키-값 상세, 페이지 크기 선택.

import { esc } from "./api.js";

const PAGE_SIZES = [50, 100, 200, 500];
const MIN_WIDTH = 60;

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
  // 가려진 열은 서버(ResultMasker)가 값을 바꿔 보낸다. 거르거나 찾으면 가림 문자와만 맞아 "그런 값이 없다"로 잘못 읽히므로 조건에서 뺀다
  const isMasked = view.columns.map((c) => masked.has(c.name));
  const state = {
    sort: null, dir: 1, filter: "", colFilters: view.columns.map(() => ""), showFilters: false,
    page: 0, pageSize: PAGE_SIZES[0], widths: view.columns.map(() => null), selected: null,
  };
  let resizedAt = 0;

  const widthStyle = (k) => (state.widths[k]
    ? ` style="width:${state.widths[k]}px;min-width:${state.widths[k]}px;max-width:${state.widths[k]}px"` : "");

  const visibleRows = () => {
    let rows = view.rows.map((r, i) => ({ r, i }));
    if (state.filter) {
      const f = state.filter.toLowerCase();
      rows = rows.filter(({ r }) => r.some((v, k) => !isMasked[k] && v !== null && String(v).toLowerCase().includes(f)));
    }
    state.colFilters.forEach((q, k) => {
      if (!q || isMasked[k]) return;
      // 대문자 NULL만 빈 값을 찾는다 — 소문자 "null"은 그 글자가 들어간 값을 찾는 보통 조건으로 둔다
      if (q === "NULL") {
        rows = rows.filter(({ r }) => r[k] === null);
        return;
      }
      const f = q.toLowerCase();
      rows = rows.filter(({ r }) => r[k] !== null && String(r[k]).toLowerCase().includes(f));
    });
    if (state.sort !== null) {
      const k = state.sort;
      rows.sort((a, b) => compare(a.r[k], b.r[k]) * state.dir);
    }
    return rows;
  };

  const detailHtml = () => {
    const row = state.selected === null ? null : view.rows[state.selected];
    if (!row) return "";
    const items = view.columns.map((c, k) => {
      const v = row[k];
      const badge = isMasked[k] ? ' <span class="mask-badge">가림</span>' : "";
      return `<div class="kv"><div class="kv-k">${esc(c.name)}${badge}<span class="kv-type">${esc(c.typeName || "")}</span></div>
        ${v === null ? '<div class="kv-v null">NULL</div>' : `<div class="kv-v">${esc(v)}</div>`}</div>`;
    }).join("");
    return `<aside class="grid-detail" aria-label="행 상세">
      <div class="grid-detail-head"><strong>행 ${state.selected + 1}</strong><span class="wb-spacer"></span>
        <button class="btn btn-small" data-row-step="-1">이전 행</button>
        <button class="btn btn-small" data-row-step="1">다음 행</button>
        <button class="btn btn-small" data-close-detail>닫기</button></div>
      ${items}</aside>`;
  };

  const draw = (focus = null) => {
    const rows = visibleRows();
    const pages = Math.max(1, Math.ceil(rows.length / state.pageSize));
    state.page = Math.min(state.page, pages - 1);
    const slice = rows.slice(state.page * state.pageSize, (state.page + 1) * state.pageSize);

    const head = view.columns.map((c, k) => {
      const arrow = state.sort === k ? (state.dir > 0 ? " ▲" : " ▼") : "";
      const badge = isMasked[k] ? ' <span class="mask-badge" title="마스킹 규칙으로 가려진 열">가림</span>' : "";
      let filter = "";
      if (state.showFilters) {
        filter = isMasked[k]
          ? '<input class="col-filter" disabled placeholder="가림" title="가려진 열은 서버가 값을 바꿔 보내 거를 수 없습니다">'
          : `<input class="col-filter" data-colfilter="${k}" placeholder="포함 · NULL" aria-label="${esc(c.name)} 거르기" value="${esc(state.colFilters[k])}">`;
      }
      return `<th data-sort="${k}" title="${esc(c.typeName || "")}"${widthStyle(k)}><span class="th-label">${esc(c.name)}${badge}${arrow}</span>${filter}<span class="col-resize" data-resize="${k}" title="끌어서 너비 조절 · 두 번 눌러 원래대로"></span></th>`;
    }).join("");
    const body = slice.map(({ r, i }) =>
      `<tr data-row="${i}"${state.selected === i ? ' class="selected"' : ""}><td class="rownum">${i + 1}</td>${r.map((v, k) => v === null
        ? `<td class="null"${widthStyle(k)}>NULL</td>`
        : `<td title="${esc(v)}"${widthStyle(k)}>${esc(v)}</td>`).join("")}</tr>`
    ).join("");
    const activeFilters = state.colFilters.filter(Boolean).length;
    const counted = rows.length === view.rows.length ? `${rows.length}행` : `${rows.length} / ${view.rows.length}행`;

    const old = container.querySelector(".grid-scroll");
    const scroll = old ? { left: old.scrollLeft, top: old.scrollTop } : { left: 0, top: 0 };
    container.innerHTML = `
      <div class="grid-bar">
        <input class="grid-filter" type="search" placeholder="결과 안에서 찾기" value="${esc(state.filter)}">
        <button class="btn btn-small" data-toggle-filters aria-pressed="${state.showFilters}">열별 거르기${activeFilters ? ` ${activeFilters}` : ""}</button>
        <span class="muted">${counted}${view.truncated ? " · 행 상한 도달(더 있음)" : ""}</span>
        <span class="wb-spacer"></span>
        <select class="grid-size" aria-label="페이지당 행 수">${PAGE_SIZES.map((n) => `<option value="${n}"${n === state.pageSize ? " selected" : ""}>${n}행씩</option>`).join("")}</select>
        <button class="btn btn-small" data-page="-1" ${state.page === 0 ? "disabled" : ""}>이전</button>
        <span class="muted">${state.page + 1} / ${pages}</span>
        <button class="btn btn-small" data-page="1" ${state.page >= pages - 1 ? "disabled" : ""}>다음</button>
      </div>
      <div class="grid-body${state.selected !== null ? " with-detail" : ""}">
        <div class="grid-scroll"><table class="grid"><thead><tr><th class="rownum">#</th>${head}</tr></thead><tbody>${body}</tbody></table></div>
        ${detailHtml()}
      </div>`;
    const scroller = container.querySelector(".grid-scroll");
    scroller.scrollLeft = scroll.left;
    scroller.scrollTop = scroll.top;

    const input = container.querySelector(".grid-filter");
    input.addEventListener("input", () => {
      state.filter = input.value;
      state.page = 0;
      draw("find");
    });
    container.querySelectorAll("input[data-colfilter]").forEach((el) => {
      el.addEventListener("click", (e) => e.stopPropagation());
      el.addEventListener("input", () => {
        state.colFilters[Number(el.dataset.colfilter)] = el.value;
        state.page = 0;
        draw(`col:${el.dataset.colfilter}`);
      });
    });
    // 다시 그리면 입력칸이 새로 만들어진다 — 치던 칸에 커서를 돌려 놓는다
    const focused = focus === "find" ? input
      : focus ? container.querySelector(`input[data-colfilter="${focus.slice(4)}"]`) : null;
    if (focused) {
      focused.focus();
      focused.setSelectionRange(focused.value.length, focused.value.length);
    }

    container.querySelectorAll("th[data-sort]").forEach((th) => th.addEventListener("click", (e) => {
      // 너비 끌기를 놓는 순간의 click이 정렬로 읽히지 않게
      if (e.target.closest(".col-resize, .col-filter") || Date.now() - resizedAt < 250) return;
      const k = Number(th.dataset.sort);
      if (isPicking()) {
        onPickColumn(view.columns[k].name);
        return;
      }
      state.dir = state.sort === k ? -state.dir : 1;
      state.sort = k;
      draw();
    }));
    container.querySelectorAll(".col-resize").forEach((handle) => {
      handle.addEventListener("pointerdown", (e) => {
        e.preventDefault();
        e.stopPropagation();
        const k = Number(handle.dataset.resize);
        const th = handle.parentElement;
        const cells = [th, ...container.querySelectorAll(`table.grid tbody td:nth-child(${k + 2})`)];
        const startX = e.clientX;
        const startWidth = th.getBoundingClientRect().width;
        handle.setPointerCapture(e.pointerId);
        const move = (ev) => {
          const w = Math.max(MIN_WIDTH, Math.round(startWidth + ev.clientX - startX));
          state.widths[k] = w;
          for (const cell of cells) cell.style.width = cell.style.minWidth = cell.style.maxWidth = `${w}px`;
        };
        const up = () => {
          handle.removeEventListener("pointermove", move);
          handle.removeEventListener("pointerup", up);
          handle.removeEventListener("pointercancel", up);
          resizedAt = Date.now();
        };
        handle.addEventListener("pointermove", move);
        handle.addEventListener("pointerup", up);
        handle.addEventListener("pointercancel", up);
      });
      handle.addEventListener("dblclick", (e) => {
        e.stopPropagation();
        state.widths[Number(handle.dataset.resize)] = null;
        draw();
      });
    });

    container.querySelector("table.grid tbody").addEventListener("click", (e) => {
      const tr = e.target.closest("tr[data-row]");
      if (!tr || isPicking()) return;
      const i = Number(tr.dataset.row);
      state.selected = state.selected === i ? null : i;
      draw();
    });
    const detail = container.querySelector(".grid-detail");
    if (detail) {
      detail.addEventListener("click", (e) => {
        if (e.target.closest("[data-close-detail]")) {
          state.selected = null;
          draw();
          return;
        }
        const step = e.target.closest("[data-row-step]");
        if (!step) return;
        // 지금 보이는 순서(찾기·거르기·정렬 뒤)대로 옮기고, 옮긴 행이 있는 페이지로 따라간다
        const order = rows.map(({ i }) => i);
        const next = order[order.indexOf(state.selected) + Number(step.dataset.rowStep)];
        if (next === undefined) return;
        state.selected = next;
        state.page = Math.floor(order.indexOf(next) / state.pageSize);
        draw();
      });
    }

    container.querySelector("[data-toggle-filters]").addEventListener("click", () => {
      state.showFilters = !state.showFilters;
      draw();
    });
    container.querySelector(".grid-size").addEventListener("change", (e) => {
      state.pageSize = Number(e.target.value);
      state.page = 0;
      draw();
    });
    container.querySelectorAll("button[data-page]").forEach((b) => b.addEventListener("click", () => {
      state.page += Number(b.dataset.page);
      draw();
    }));
  };
  draw();
}
