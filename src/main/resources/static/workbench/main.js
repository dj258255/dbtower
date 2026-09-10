// 거버넌스 SQL 워크벤치 — 인스턴스·스키마 트리·탭 편집기·결과·실행 기록을 잇는다.
// 정책(분류·계정·읽기 전용·마스킹·기록)은 전부 서버가 강제한다. 이 화면은 그 결과를 보여줄 뿐 판정하지 않는다.

import { request, esc, csrfToken, ApiError } from "./api.js";
import { SqlEditor } from "./editor.js";
import { renderTree } from "./schema-tree.js";
import { renderGrid } from "./grid.js";

const $ = (id) => document.getElementById(id);

// 트리 더블클릭 미리보기 — 기종마다 행 제한 문법이 달라 화면 편의용 템플릿만 둔다(실행 판정은 서버가 한다)
const PREVIEW = {
  MYSQL: (t) => `SELECT *\nFROM ${t}\nLIMIT 50`,
  POSTGRESQL: (t) => `SELECT *\nFROM ${t}\nLIMIT 50`,
  MSSQL: (t) => `SELECT TOP 50 *\nFROM ${t}`,
  ORACLE: (t) => `SELECT *\nFROM ${t}\nFETCH FIRST 50 ROWS ONLY`,
  MONGODB: (t) => `{"find": "${t}", "filter": {}, "limit": 50}`,
};

const TIER = {
  READ: ["읽기 · 즉시 실행", "read"],
  NEEDS_APPROVAL: ["변경 · 승인 필요", "change"],
  BLOCKED: ["차단", "blocked"],
};

const store = {
  get(key, fallback) {
    try {
      const v = localStorage.getItem(key);
      return v ? JSON.parse(v) : fallback;
    } catch {
      return fallback;
    }
  },
  set(key, value) {
    try {
      localStorage.setItem(key, JSON.stringify(value));
    } catch {
      // 저장소를 못 쓰는 환경(사생활 보호 모드 등)이면 탭 복원만 포기한다
    }
  },
};

const state = {
  instances: [],
  instance: null,
  schema: null,
  expanded: new Set(),
  tabs: [],
  active: 0,
  classifyTimer: null,
  classifySeq: 0,
  saveTimer: null,
};

const editor = new SqlEditor({
  input: $("wb-input"),
  highlighter: $("wb-highlight"),
  complete: $("wb-complete"),
  onRun: run,
  onChange: onEdit,
});

init();

async function init() {
  bindChrome();
  request("/api/me").then((me) => { $("user-chip").textContent = `${me.username} · ${me.role}`; }).catch(() => {});
  try {
    state.instances = await request("/api/workbench/instances");
  } catch (e) {
    $("wb-tree").textContent = `인스턴스 목록을 불러오지 못했습니다: ${e.message}`;
    return;
  }
  const select = $("wb-instance");
  select.innerHTML = state.instances.length
    ? state.instances.map((i) =>
      `<option value="${esc(i.id)}">${esc(i.name)} · ${esc(i.type)}${i.readConfigured ? "" : " (조회 계정 없음)"}</option>`).join("")
    : '<option value="">볼 수 있는 인스턴스가 없습니다</option>';
  const wanted = new URLSearchParams(location.search).get("instance");
  if (wanted && state.instances.some((i) => String(i.id) === wanted)) select.value = wanted;
  select.addEventListener("change", () => selectInstance(select.value));
  if (select.value) selectInstance(select.value);
}

function bindChrome() {
  $("logout-btn").addEventListener("click", async () => {
    await fetch("/logout", { method: "POST", headers: { "X-XSRF-TOKEN": csrfToken() } });
    location.href = "/login.html";
  });
  $("wb-run").addEventListener("click", run);
  $("wb-export").addEventListener("click", openExport);
  $("wb-modal-cancel").addEventListener("click", () => { $("wb-modal").hidden = true; });
  $("wb-modal-ok").addEventListener("click", doExport);
  $("wb-tree-filter").addEventListener("input", drawTree);
  document.querySelectorAll(".wb-rtab").forEach((b) => b.addEventListener("click", () => showPane(b.dataset.pane)));
}

async function selectInstance(id) {
  state.instance = state.instances.find((i) => String(i.id) === String(id));
  if (!state.instance) return;
  history.replaceState(null, "", `?instance=${encodeURIComponent(id)}`);

  const note = $("wb-instance-note");
  note.hidden = state.instance.readConfigured;
  note.textContent = "이 인스턴스에는 조회 계정(READ)이 없어 실행이 거부됩니다. ADMIN이 콘솔 계정을 등록해야 합니다.";

  state.tabs = store.get(tabsKey(), null) || [{ title: "쿼리 1", sql: "" }];
  state.active = Math.min(store.get(`${tabsKey()}:active`, 0), state.tabs.length - 1);
  drawTabs();
  editor.value = state.tabs[state.active].sql;
  classifySoon();

  state.schema = null;
  state.expanded = new Set();
  $("wb-tree").textContent = "스키마를 불러오는 중...";
  try {
    state.schema = await request(`/api/instances/${encodeURIComponent(id)}/schema`);
    editor.setSchema(state.schema);
  } catch (e) {
    state.schema = null;
  }
  drawTree();
  loadHistory();
}

const tabsKey = () => `wb:tabs:${state.instance.id}`;

function drawTree() {
  renderTree($("wb-tree"), state.schema, {
    filter: $("wb-tree-filter").value,
    expanded: state.expanded,
    onInsert: (name) => editor.insert(name),
    onPreview: (table) => openTab(table, (PREVIEW[state.instance.type] || PREVIEW.MYSQL)(table)),
    onToggle: (table) => {
      if (state.expanded.has(table)) state.expanded.delete(table);
      else state.expanded.add(table);
      drawTree();
    },
  });
}

function drawTabs() {
  $("wb-tabs").innerHTML = state.tabs.map((t, i) =>
    `<button class="wb-tab ${i === state.active ? "active" : ""}" data-i="${i}">${esc(t.title)}${state.tabs.length > 1 ? '<span class="x" data-close="' + i + '" title="닫기">×</span>' : ""}</button>`
  ).join("") + '<button class="wb-tab-add" title="새 탭">+</button>';
  $("wb-tabs").onclick = (e) => {
    const close = e.target.closest("[data-close]");
    if (close) {
      closeTab(Number(close.dataset.close));
      return;
    }
    if (e.target.closest(".wb-tab-add")) {
      openTab(null, "");
      return;
    }
    const tab = e.target.closest(".wb-tab");
    if (tab) switchTab(Number(tab.dataset.i));
  };
}

function openTab(title, sql) {
  state.tabs.push({ title: title || `쿼리 ${state.tabs.length + 1}`, sql });
  switchTab(state.tabs.length - 1);
}

function switchTab(i) {
  state.active = i;
  editor.value = state.tabs[i].sql;
  drawTabs();
  persistTabs();
  classifySoon();
  editor.focus();
}

function closeTab(i) {
  state.tabs.splice(i, 1);
  state.active = Math.min(state.active, state.tabs.length - 1);
  switchTab(state.active);
}

function onEdit(sql) {
  state.tabs[state.active].sql = sql;
  clearTimeout(state.saveTimer);
  state.saveTimer = setTimeout(persistTabs, 400);
  classifySoon();
}

function persistTabs() {
  if (!state.instance) return;
  store.set(tabsKey(), state.tabs);
  store.set(`${tabsKey()}:active`, state.active);
}

// 입력이 멈추면 서버 분류기에 물어 배지를 갱신한다 — 실행 전에 "이건 즉시 안 돈다"를 보여주기 위해
function classifySoon() {
  clearTimeout(state.classifyTimer);
  state.classifyTimer = setTimeout(classify, 350);
}

async function classify() {
  const badge = $("wb-class");
  const sql = editor.statementToRun();
  if (!state.instance || !sql.trim()) {
    badge.textContent = "";
    badge.className = "wb-class";
    return;
  }
  const seq = ++state.classifySeq;
  try {
    const c = await request(`/api/workbench/instances/${state.instance.id}/classify`, { method: "POST", body: { sql } });
    if (seq !== state.classifySeq) return;
    const [label, cls] = TIER[c.tier] || [c.tier, ""];
    badge.textContent = `${label} · ${c.kind}`;
    badge.className = `wb-class ${cls}`;
    badge.title = c.reason;
  } catch {
    // 분류 배지는 안내일 뿐이다 — 실패해도 실행 시점에 서버가 다시 판정한다
  }
}

async function run() {
  if (!state.instance) return;
  const sql = editor.statementToRun();
  if (!sql.trim()) return;
  const button = $("wb-run");
  button.disabled = true;
  $("wb-status").textContent = "실행 중...";
  showPane("grid");
  try {
    const view = await request(`/api/workbench/instances/${state.instance.id}/query`, {
      method: "POST",
      body: { sql, rowLimit: Number($("wb-limit").value) },
    });
    renderGrid($("wb-grid"), view);
    const maskedNote = view.maskedColumns.length ? ` · 가린 열: ${view.maskedColumns.join(", ")}` : "";
    $("wb-status").textContent = `${view.rowCount}행${view.truncated ? "+" : ""} · ${view.elapsedMs}ms${maskedNote}`;
  } catch (e) {
    showFailure(e);
  } finally {
    button.disabled = false;
    loadHistory();
  }
}

function showFailure(e) {
  const grid = $("wb-grid");
  $("wb-status").textContent = "";
  if (!(e instanceof ApiError)) {
    grid.innerHTML = `<div class="wb-msg error"><strong>요청 실패</strong><p>${esc(e.message)}</p></div>`;
    return;
  }
  const c = e.body.classification;
  if (e.status === 409 && c && c.tier === "NEEDS_APPROVAL") {
    grid.innerHTML = `<div class="wb-msg change"><strong>변경 요청이 필요한 문장입니다 (${esc(c.kind)})</strong>
      <p>${esc(c.reason)}</p>
      <p class="muted">워크벤치는 조회만 즉시 실행합니다. 데이터·구조 변경은 승인 티켓으로 올려 변경 계정으로 실행합니다.</p></div>`;
  } else if (e.status === 400 && c) {
    grid.innerHTML = `<div class="wb-msg blocked"><strong>차단된 문장입니다 (${esc(c.kind)})</strong><p>${esc(c.reason)}</p></div>`;
  } else if (e.status === 422) {
    grid.innerHTML = `<div class="wb-msg error"><strong>대상 DB가 문장을 거부했습니다</strong><pre>${esc(e.message)}</pre></div>`;
  } else {
    grid.innerHTML = `<div class="wb-msg error"><strong>실행하지 않았습니다 (${esc(e.status)})</strong><p>${esc(e.message)}</p></div>`;
  }
}

function openExport() {
  if (!state.instance || !editor.statementToRun().trim()) return;
  $("wb-reason").value = "";
  $("wb-modal").hidden = false;
  $("wb-reason").focus();
}

async function doExport() {
  const reason = $("wb-reason").value;
  $("wb-modal").hidden = true;
  $("wb-status").textContent = "내보내는 중...";
  try {
    const res = await request(`/api/workbench/instances/${state.instance.id}/export`, {
      method: "POST",
      body: { sql: editor.statementToRun(), reason },
      raw: true,
    });
    const blob = await res.blob();
    const disposition = res.headers.get("Content-Disposition") || "";
    const encoded = disposition.match(/filename\*=UTF-8''([^;]+)/);
    const filename = encoded ? decodeURIComponent(encoded[1]) : "workbench.csv";
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = filename;
    a.click();
    URL.revokeObjectURL(url);
    $("wb-status").textContent = `${res.headers.get("X-Row-Count")}행 내보냄${res.headers.get("X-Truncated") === "true" ? " (상한 도달)" : ""}`;
  } catch (e) {
    showFailure(e);
  } finally {
    loadHistory();
  }
}

async function loadHistory() {
  if (!state.instance) return;
  const box = $("wb-history");
  try {
    const items = await request(`/api/workbench/instances/${state.instance.id}/history?limit=30`);
    if (!items.length) {
      box.innerHTML = '<div class="muted">이 인스턴스에서 실행한 기록이 없습니다.</div>';
      return;
    }
    box.innerHTML = `<table class="history"><tbody>${items.map((h, i) => `
      <tr data-i="${i}">
        <td class="muted">${esc(String(h.occurredAt).replace("T", " ").slice(0, 19))}</td>
        <td><span class="outcome ${esc(h.outcome)}">${esc(h.outcome)}</span> ${esc(h.action)}</td>
        <td class="muted">${esc(h.kind)}${h.rowCount !== null ? ` · ${esc(h.rowCount)}행` : ""}</td>
        <td><code>${esc(h.statement.slice(0, 300))}</code>${h.error ? `<div class="muted">${esc(h.error.slice(0, 200))}</div>` : ""}</td>
      </tr>`).join("")}</tbody></table>
      <p class="hint">기록에는 리터럴 값이 가려진 문장이 남습니다. 행을 누르면 새 탭으로 엽니다.</p>`;
    box.onclick = (e) => {
      const row = e.target.closest("tr[data-i]");
      if (row) openTab(null, items[Number(row.dataset.i)].statement);
    };
  } catch (e) {
    box.textContent = `기록을 불러오지 못했습니다: ${e.message}`;
  }
}

function showPane(name) {
  document.querySelectorAll(".wb-rtab").forEach((b) => b.classList.toggle("active", b.dataset.pane === name));
  $("wb-pane-grid").hidden = name !== "grid";
  $("wb-pane-history").hidden = name !== "history";
}
