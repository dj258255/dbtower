// 거버넌스 SQL 워크벤치 — 인스턴스·워크시트·편집기·결과 미리보기·AI 대화를 잇는다.
// 정책(분류·계정·읽기 전용·마스킹·기록)과 판정은 전부 서버가 강제한다. 이 화면은 결과를 보여줄 뿐 판정하지 않는다.

import { request, streamEvents, esc, csrfToken, ApiError, localTime } from "./api.js";
import { SqlEditor, highlight } from "./editor.js";
import { renderTree } from "./schema-tree.js";
import { renderGrid } from "./grid.js";
import { renderTimeline, renderChips, updatePending } from "./chat.js";
import { renderDiff } from "./diff.js";
import { TicketPanel } from "./tickets.js";

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

const state = {
  me: null,
  instances: [],
  instance: null,
  schema: null,
  expanded: new Set(),
  sheets: [],
  sheet: null,
  timeline: [],
  chips: [],
  picking: false,
  allowValues: false,
  lastView: null,
  lastFailure: null,
  pendingQuestion: null,
  classifyTimer: null,
  classifySeq: 0,
  saveTimer: null,
  pendingTicket: null,
};

// 버튼은 역할 이름이 아니라 능력(/api/me capabilities)으로 가른다 — 표시만이고 인가는 서버가 한다
const can = (cap) => Boolean(state.me && (state.me.capabilities || []).includes(cap));
const ROLE_LABEL = { VIEWER: "관제", REQUESTER: "요청자", APPROVER: "승인자", OPERATOR: "운영자", ADMIN: "관리자" };

const editor = new SqlEditor({
  input: $("wb-input"),
  highlighter: $("wb-highlight"),
  complete: $("wb-complete"),
  onRun: () => run(),
  onChange: onEdit,
});

const tickets = new TicketPanel({
  list: $("wb-tickets"),
  detail: $("wb-ticket"),
  count: $("wb-ticket-count"),
  can: (cap) => can(cap),
  me: () => (state.me ? state.me.username : null),
  onOpenSql: (sql) => { editor.value = sql; onEdit(); editor.focus(); },
  onProposeTicket: (sql, reason) => openTicket(sql, reason),
});

init();

async function init() {
  bindChrome();
  try {
    state.me = await request("/api/me");
    $("user-chip").textContent = `${state.me.username} · ${ROLE_LABEL[state.me.role] || state.me.role}`;
  } catch {
    // 표시만 생략한다 — 권한 판정은 서버가 한다
  }
  if (state.me && !can("WORKBENCH")) {
    // 관제만 보는 역할은 대상 DB의 행 값을 보지 않는다. 서버도 워크벤치 API를 403으로 막지만, 실패 문구가 늘어선 빈 화면 대신 갈 곳을 알려준다
    // 셸 전체를 바꾼다 — 셸은 좌·중·우 격자라 안쪽에 넣으면 안내가 왼쪽 첫 칸(인스턴스 목록 폭)에 끼어 세로로 늘어진다(136절 화면 확인)
    document.querySelector(".wb-shell").outerHTML = `<main style="padding:48px 16px">
      <div class="wb-note" style="margin:0 auto;max-width:640px">
        <p>워크벤치는 조회 계정으로 대상 DB의 행 값을 보는 화면이라 요청자(REQUESTER) 이상 역할이 필요합니다.
          지금 역할(${esc(ROLE_LABEL[state.me.role] || state.me.role)})은 대시보드에서 지표·리포트를 봅니다.</p>
        <p><a class="btn btn-primary btn-small" href="/">대시보드로</a></p></div></main>`;
    return;
  }
  const handoff = takeHandoff(new URLSearchParams(location.search).get("handoff"));
  try {
    state.instances = await request("/api/workbench/instances");
  } catch (e) {
    $("wb-sheets").innerHTML = `<li class="muted">인스턴스 목록을 불러오지 못했습니다: ${esc(e.message)}</li>`;
    return;
  }
  const select = $("wb-instance");
  select.innerHTML = state.instances.length
    ? state.instances.map((i) =>
      `<option value="${esc(i.id)}">${esc(i.name)} · ${esc(i.type)}${i.readConfigured ? "" : " (조회 계정 없음)"}</option>`).join("")
    : '<option value="">볼 수 있는 인스턴스가 없습니다</option>';
  const params = new URLSearchParams(location.search);
  const wanted = params.get("instance");
  if (wanted && state.instances.some((i) => String(i.id) === wanted)) select.value = wanted;
  select.addEventListener("change", () => selectInstance(select.value, null));
  state.pendingTicket = params.get("ticket");
  if (!select.value) return;
  await selectInstance(select.value, params.get("sheet"));
  // 넘김은 요청한 인스턴스가 실제로 열렸을 때만 받는다 — 팀 범위 밖이라 다른 인스턴스가 열렸는데 SQL을 채우면 엉뚱한 대상에 요청이 올라간다
  if (handoff && wanted === select.value) await receiveHandoff(handoff);
}

// 대시보드가 localStorage에 남긴 넘김(app.js handToWorkbench)을 한 번만 읽는다. 10분 지난 넘김은 버린다 —
// 예전 탭을 새로고침했을 때 오래된 SQL이 다시 채워지지 않게
function takeHandoff(id) {
  if (!id || !/^[a-z0-9]{6,24}$/.test(id)) return null;
  const key = `dbtower.handoff.${id}`;
  try {
    const raw = localStorage.getItem(key);
    localStorage.removeItem(key);
    const h = raw ? JSON.parse(raw) : null;
    return h && typeof h.sql === "string" && h.sql.trim() && Date.now() - h.at < 10 * 60000 ? h : null;
  } catch {
    return null;
  }
}

async function receiveHandoff(h) {
  if (h.kind === "draft") {
    // 제안 DDL은 편집기가 아니라 변경 요청 창으로 — 요청자가 사유를 보태 올리면 규칙 판정·승인·드라이런을 거친다
    openTicket(h.sql, h.reason || "");
    return;
  }
  // 조회 SQL은 새 워크시트에 채운다 — 지금 열린 워크시트의 작업을 덮어쓰지 않게
  await createSheet("대시보드에서 넘긴 쿼리");
  editor.value = h.sql;
  onEdit();
  editor.focus();
}

function bindChrome() {
  $("logout-btn").addEventListener("click", async () => {
    await fetch("/logout", { method: "POST", headers: { "X-XSRF-TOKEN": csrfToken() } });
    location.href = "/login.html";
  });
  $("wb-run").addEventListener("click", () => run());
  $("wb-export").addEventListener("click", openExport);
  $("wb-modal-cancel").addEventListener("click", () => { $("wb-modal").hidden = true; });
  $("wb-modal-ok").addEventListener("click", doExport);
  $("wb-tree-filter").addEventListener("input", drawTree);
  $("wb-sheet-add").addEventListener("click", () => createSheet());
  $("wb-title").addEventListener("change", renameSheet);
  $("wb-title").addEventListener("keydown", (e) => { if (e.key === "Enter") e.target.blur(); });
  $("wb-send").addEventListener("click", ask);
  $("wb-ask").addEventListener("keydown", (e) => {
    if ((e.ctrlKey || e.metaKey) && e.key === "Enter") ask();
  });
  $("wb-pick").addEventListener("click", () => setPicking(!state.picking));
  $("wb-cmp-run").addEventListener("click", runCompare);
  $("wb-ticket-cancel").addEventListener("click", () => { $("wb-ticket-modal").hidden = true; });
  $("wb-ticket-ok").addEventListener("click", submitTicket);
  document.querySelectorAll(".wb-rtab").forEach((b) => b.addEventListener("click", () => showPane(b.dataset.pane)));
  document.querySelectorAll(".wb-ctab").forEach((b) => b.addEventListener("click", () => showChatPane(b.dataset.cpane)));
}

// ---------- 인스턴스·워크시트 ----------

async function selectInstance(id, sheetId) {
  state.instance = state.instances.find((i) => String(i.id) === String(id));
  if (!state.instance) return;
  const note = $("wb-instance-note");
  note.hidden = state.instance.readConfigured;
  note.textContent = "이 인스턴스에는 조회 계정(READ)이 없어 실행이 거부됩니다. ADMIN이 콘솔 계정을 등록해야 합니다.";

  state.schema = null;
  state.expanded = new Set();
  $("wb-tree").textContent = "스키마를 불러오는 중...";
  request(`/api/instances/${encodeURIComponent(id)}/schema`)
    .then((schema) => { state.schema = schema; editor.setSchema(schema); })
    .catch(() => { state.schema = null; })
    .finally(() => {
      drawTree();
      $("wb-schema-count").textContent = state.schema ? state.schema.tables.length : "";
    });
  request(`/api/workbench/instances/${encodeURIComponent(id)}/settings`)
    .then((s) => { state.allowValues = s.allowAiResultValues; drawShare(); })
    .catch(() => { state.allowValues = false; drawShare(); });
  drawCompareTargets();
  const wantedTicket = state.pendingTicket;
  state.pendingTicket = null;
  tickets.load(id, wantedTicket).then(() => { if (wantedTicket) showChatPane("tickets"); });

  state.sheets = await request(`/api/workbench/instances/${encodeURIComponent(id)}/worksheets`);
  if (!state.sheets.length) {
    await createSheet();
    return;
  }
  const target = state.sheets.find((s) => String(s.id) === String(sheetId)) || state.sheets[0];
  openSheet(target.id);
}

function drawSheets() {
  $("wb-sheets").innerHTML = state.sheets.map((s) => `
    <li data-id="${esc(s.id)}" class="${state.sheet && state.sheet.id === s.id ? "active" : ""}">
      <span class="name">${esc(s.title)}</span>
      <span class="ver">${s.latestVersion ? "v" + esc(s.latestVersion) : ""}</span>
      <button class="x" data-archive="${esc(s.id)}" title="보관">×</button>
    </li>`).join("");
  $("wb-sheets").onclick = async (e) => {
    const archive = e.target.closest("[data-archive]");
    if (archive) {
      await request(`/api/workbench/worksheets/${archive.dataset.archive}`, { method: "DELETE" });
      await reloadSheets();
      if (state.sheet && String(state.sheet.id) === archive.dataset.archive) {
        state.sheets.length ? openSheet(state.sheets[0].id) : createSheet();
      }
      return;
    }
    const li = e.target.closest("li[data-id]");
    if (li) openSheet(Number(li.dataset.id));
  };
}

async function reloadSheets() {
  state.sheets = await request(`/api/workbench/instances/${state.instance.id}/worksheets`);
  if (state.sheet) state.sheet = state.sheets.find((s) => s.id === state.sheet.id) || state.sheet;
  // AI 제안·실행·되돌리기 어느 경로로 버전이 늘어도 상단 표시가 따라가게 한 곳에서 갱신한다
  if (state.sheet) $("wb-version").textContent = state.sheet.latestVersion ? `최신 v${state.sheet.latestVersion}` : "버전 없음";
  drawSheets();
}

async function createSheet(title = "") {
  const sheet = await request(`/api/workbench/instances/${state.instance.id}/worksheets`, { method: "POST", body: { title } });
  await reloadSheets();
  // 여는 것까지 기다린다 — 넘겨받은 SQL을 채운 뒤에 openSheet가 편집기를 빈 값으로 되돌리지 않게
  await openSheet(sheet.id);
}

async function openSheet(id) {
  await flushSave();
  state.sheet = state.sheets.find((s) => s.id === id);
  if (!state.sheet) return;
  history.replaceState(null, "", `?instance=${encodeURIComponent(state.instance.id)}&sheet=${encodeURIComponent(id)}`);
  $("wb-title").value = state.sheet.title;
  $("wb-version").textContent = state.sheet.latestVersion ? `최신 v${state.sheet.latestVersion}` : "버전 없음";
  editor.value = state.sheet.currentSql || "";
  state.chips = [];
  state.lastView = null;
  hideOverlay();
  $("wb-grid").innerHTML = '<div class="muted">문장을 실행하면 결과가 여기에 나옵니다.</div>';
  drawSheets();
  drawChips();
  drawShare();
  classifySoon();
  loadTimeline();
  loadHistory();
}

async function renameSheet() {
  const title = $("wb-title").value.trim();
  if (!state.sheet || !title || title === state.sheet.title) return;
  await request(`/api/workbench/worksheets/${state.sheet.id}`, { method: "PATCH", body: { title } });
  await reloadSheets();
}

function onEdit() {
  clearTimeout(state.saveTimer);
  state.saveTimer = setTimeout(flushSave, 800);
  classifySoon();
}

// 편집기 자동 저장 — 버전이 아니라 워크시트의 현재 내용이다(버전은 실행·AI 제안·되돌리기에서만 생긴다)
async function flushSave() {
  clearTimeout(state.saveTimer);
  if (!state.sheet || editor.value === (state.sheet.currentSql || "")) return;
  const sql = editor.value;
  try {
    await request(`/api/workbench/worksheets/${state.sheet.id}`, { method: "PATCH", body: { currentSql: sql } });
    state.sheet.currentSql = sql;
  } catch {
    // 저장 실패는 다음 편집에서 다시 시도된다
  }
}

// ---------- 스키마·선택 모드 ----------

function drawTree() {
  renderTree($("wb-tree"), state.schema, {
    filter: $("wb-tree-filter").value,
    expanded: state.expanded,
    onInsert: (name) => editor.insert(name),
    onPreview: (table) => previewSql((PREVIEW[state.instance.type] || PREVIEW.MYSQL)(table)),
    onToggle: (table) => {
      if (state.expanded.has(table)) state.expanded.delete(table);
      else state.expanded.add(table);
      drawTree();
    },
    isPicking: () => state.picking,
    onPick: addChip,
    onDetail: openTableDetail,
  });
}

// ---------- 테이블 상세 ----------

const bytes = (n) => {
  if (n === null || n === undefined || n < 0) return "미확보";
  const units = ["B", "KB", "MB", "GB", "TB"];
  let v = n;
  let u = 0;
  while (v >= 1024 && u < units.length - 1) { v /= 1024; u++; }
  return `${v.toFixed(u ? 1 : 0)} ${units[u]}`;
};

// 행 수·크기·인덱스·DDL은 모니터 계정의 카탈로그 조회(기존 테이블 상세 API)로, 열 목록은 이미 받은 스키마 트리로 채운다
async function openTableDetail(name) {
  if (!state.instance) return;
  showPane("table");
  const box = $("wb-table");
  box.className = "";
  box.innerHTML = `<div class="muted">${esc(name)} 상세를 불러오는 중...</div>`;
  const table = state.schema ? state.schema.tables.find((t) => t.name === name) : null;
  let d;
  try {
    d = await request(`/api/instances/${encodeURIComponent(state.instance.id)}/table-detail`, { method: "POST", body: { table: name } });
  } catch (e) {
    box.innerHTML = `<div class="wb-msg error"><strong>상세를 불러오지 못했습니다</strong><p>${esc(e.message)}</p></div>`;
    return;
  }
  const preview = (PREVIEW[state.instance.type] || PREVIEW.MYSQL)(name);
  const columns = table ? table.columns.map((c) => `<tr><td><button class="td-col" data-col="${esc(c.name)}" title="채팅에 붙이기">${esc(c.name)}</button></td>
      <td class="muted">${esc(c.type)}</td><td class="muted">${c.nullable ? "NULL" : "NOT NULL"}</td></tr>`).join("") : "";
  const indexes = (d.indexes || []).map((i) => `<tr><td>${esc(i.name)}</td><td>${esc((i.columns || []).join(", "))}</td>
      <td class="muted">${i.unique ? "UNIQUE" : ""}</td><td class="muted">${esc(i.type || "")}</td>
      <td class="muted">${i.cardinality === null || i.cardinality === undefined ? "미확보" : esc(i.cardinality)}</td></tr>`).join("");
  box.innerHTML = `
    <div class="td-head"><strong>${esc(d.table || name)}</strong>${d.engine ? `<span class="muted">${esc(d.engine)}</span>` : ""}
      <span class="wb-spacer"></span>
      <button class="btn btn-small" data-td="preview">미리보기 실행</button>
      <button class="btn btn-small" data-td="editor">SELECT 편집기로</button>
      <button class="btn btn-small" data-td="chip">채팅에 붙이기</button></div>
    <div class="td-stats">
      <div><span class="wb-label">행 수(추정)</span><b>${d.rowCount >= 0 ? esc(d.rowCount.toLocaleString("ko-KR")) : "미확보"}</b></div>
      <div><span class="wb-label">데이터</span><b>${esc(bytes(d.dataBytes))}</b></div>
      <div><span class="wb-label">인덱스</span><b>${esc(bytes(d.indexBytes))}</b></div>
      <div><span class="wb-label">평균 행</span><b>${esc(bytes(d.avgRowBytes))}</b></div>
      <div><span class="wb-label">생성</span><b>${esc(d.createdAt || "미확보")}</b></div>
    </div>
    ${d.note ? `<div class="hint">${esc(d.note)}</div>` : ""}
    <div class="td-grid">
      <section><div class="df-title">열 ${table ? table.columns.length : ""}</div>
        ${columns ? `<table class="history"><tbody>${columns}</tbody></table>` : '<div class="muted">스키마 트리에 없는 테이블입니다.</div>'}</section>
      <section><div class="df-title">인덱스 ${(d.indexes || []).length}</div>
        ${indexes ? `<table class="history"><thead><tr><th>이름</th><th>열</th><th></th><th>타입</th><th>카디널리티</th></tr></thead><tbody>${indexes}</tbody></table>` : '<div class="muted">인덱스가 없거나 확보하지 못했습니다.</div>'}</section>
    </div>
    <div class="df-title">DDL <span class="muted">${esc({ NATIVE: "엔진이 준 원문", RECONSTRUCTED: "카탈로그로 재구성한 근사", UNSUPPORTED: "미지원" }[d.ddlSource] || d.ddlSource || "")}</span></div>
    ${d.ddl ? `<pre class="ai-sql td-ddl"><code>${highlight(d.ddl)}</code></pre>` : '<div class="muted">DDL을 확보하지 못했습니다.</div>'}`;
  box.onclick = (e) => {
    const col = e.target.closest("[data-col]");
    if (col) { addChip({ type: "column", value: `${name}.${col.dataset.col}` }); showChatPane("chat"); return; }
    const b = e.target.closest("[data-td]");
    if (!b) return;
    if (b.dataset.td === "preview") { showPane("grid"); previewSql(preview); }
    if (b.dataset.td === "editor") { editor.value = preview; onEdit(); editor.focus(); }
    if (b.dataset.td === "chip") { addChip({ type: "table", value: name }); showChatPane("chat"); }
  };
}

function setPicking(on) {
  state.picking = on;
  $("wb-pick").setAttribute("aria-pressed", String(on));
  document.body.classList.toggle("picking", on);
}

function addChip(chip) {
  if (!state.chips.some((c) => c.type === chip.type && c.value === chip.value)) state.chips.push(chip);
  drawChips();
}

function drawChips() {
  renderChips($("wb-chips"), state.chips, (i) => { state.chips.splice(i, 1); drawChips(); });
}

function drawShare() {
  const canShare = state.allowValues && state.lastView && state.lastView.rows.length > 0;
  $("wb-share-wrap").hidden = !canShare;
  if (!canShare) $("wb-share").checked = false;
  $("wb-share-note").textContent = state.allowValues
    ? "이 인스턴스는 마스킹된 결과 값을 AI에 보낼 수 있습니다(최대 20행)."
    : "이 인스턴스는 조회 결과 값을 AI에 보내지 않습니다. AI는 스키마와 대화만 봅니다.";
  $("wb-backend").textContent = "";
}

// ---------- 실행 ----------

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
    // 분류 배지는 안내일 뿐이다 — 실행 시점에 서버가 다시 판정한다
  }
}

function previewSql(sql) {
  editor.value = sql;
  onEdit();
  run(sql);
}

async function run(explicitSql) {
  if (!state.instance || !state.sheet) return;
  const sql = explicitSql || editor.statementToRun();
  if (!sql.trim()) return;
  $("wb-run").disabled = true;
  $("wb-status").textContent = "실행 중...";
  showPane("grid");
  try {
    const res = await request(`/api/workbench/instances/${state.instance.id}/query`, {
      method: "POST",
      body: { sql, rowLimit: Number($("wb-limit").value), worksheetId: state.sheet.id },
    });
    const view = res.result;
    state.lastView = view;
    state.lastFailure = null;
    hideOverlay();
    renderGrid($("wb-grid"), view, { isPicking: () => state.picking, onPickColumn: (name) => addChip({ type: "column", value: name }) });
    const maskedNote = view.maskedColumns.length ? ` · 가린 열: ${view.maskedColumns.join(", ")}` : "";
    $("wb-status").textContent = `${view.rowCount}행${view.truncated ? "+" : ""} · ${view.elapsedMs}ms${maskedNote}`;
    drawShare();
    if (res.version) {
      await reloadSheets();
      $("wb-version").textContent = `최신 v${res.version.versionNo}`;
      loadTimeline();
    }
  } catch (e) {
    state.lastFailure = { sql, error: e };
    showFailure(e);
  } finally {
    $("wb-run").disabled = false;
    loadHistory();
  }
}

function hideOverlay() {
  $("wb-overlay").hidden = true;
}

// 실패해도 마지막 정상 결과는 그대로 두고 그 위에 오류를 겹친다
function showFailure(e) {
  $("wb-status").textContent = "";
  const overlay = $("wb-overlay");
  let body;
  let ticketButton = "";
  const c = e instanceof ApiError ? e.body.classification : null;
  if (e instanceof ApiError && e.status === 409 && c && c.tier === "NEEDS_APPROVAL") {
    body = `<div class="wb-msg change"><strong>변경 요청이 필요한 문장입니다 (${esc(c.kind)})</strong>
      <p>${esc(c.reason)}</p><p class="muted">워크벤치는 조회만 즉시 실행합니다. 데이터·구조 변경은 승인 티켓으로 올려 변경 계정으로 실행합니다.</p>`;
    ticketButton = '<button class="btn btn-small btn-primary" data-overlay="ticket">변경 요청으로 올리기</button>';
  } else if (e instanceof ApiError && e.status === 400 && c) {
    body = `<div class="wb-msg blocked"><strong>차단된 문장입니다 (${esc(c.kind)})</strong><p>${esc(c.reason)}</p>`;
  } else if (e instanceof ApiError && e.status === 422) {
    body = `<div class="wb-msg error"><strong>대상 DB가 문장을 거부했습니다</strong><pre>${esc(e.message)}</pre>`;
  } else {
    body = `<div class="wb-msg error"><strong>실행하지 않았습니다${e instanceof ApiError ? " (" + esc(e.status) + ")" : ""}</strong><p>${esc(e.message)}</p>`;
  }
  overlay.innerHTML = `${body}<div class="wb-overlay-actions">
      ${ticketButton}
      <button class="btn btn-small" data-overlay="fix">AI로 고치기</button>
      <button class="btn btn-small" data-overlay="close">닫기</button></div></div>`;
  overlay.hidden = false;
  overlay.onclick = (ev) => {
    const b = ev.target.closest("[data-overlay]");
    if (!b) return;
    if (b.dataset.overlay === "close") hideOverlay();
    if (b.dataset.overlay === "ticket") {
      hideOverlay();
      openTicket(state.lastFailure ? state.lastFailure.sql : editor.statementToRun());
    }
    if (b.dataset.overlay === "fix") {
      hideOverlay();
      $("wb-ask").value = "이 SQL이 실패했어. 원인을 짚고 고쳐줘.";
      showChatPane("chat");
      ask();
    }
  };
}

// ---------- AI 대화 ----------

async function loadTimeline() {
  if (!state.sheet) return;
  try {
    state.timeline = await request(`/api/workbench/worksheets/${state.sheet.id}/timeline`);
  } catch {
    state.timeline = [];
  }
  drawTimeline();
}

function drawTimeline() {
  renderTimeline($("wb-timeline"), state.timeline, {
    currentVersion: state.sheet ? state.sheet.latestVersion : null,
    pending: state.pendingQuestion,
    onApply: (sql) => { editor.value = sql; onEdit(); editor.focus(); },
    onPreview: (sql) => previewSql(sql),
    onRestore: async (versionNo) => {
      const v = await request(`/api/workbench/worksheets/${state.sheet.id}/versions/${versionNo}/restore`, { method: "POST" });
      await reloadSheets();
      editor.value = v.sql;
      state.sheet.currentSql = v.sql;
      $("wb-version").textContent = `최신 v${v.versionNo}`;
      classifySoon();
      loadTimeline();
    },
  });
}

async function ask() {
  const message = $("wb-ask").value.trim();
  if (!message || !state.sheet || state.pendingQuestion) return;
  await flushSave();
  const failure = state.lastFailure;
  const share = $("wb-share").checked && state.lastView;
  const body = {
    message,
    tables: state.chips.filter((c) => c.type === "table").map((c) => c.value),
    columns: state.chips.filter((c) => c.type === "column").map((c) => c.value),
    failedSql: failure ? failure.sql : null,
    failedError: failure ? failure.error.message : null,
    result: share ? { columns: state.lastView.columns.map((c) => c.name), rows: state.lastView.rows.slice(0, 20) } : null,
  };
  state.pendingQuestion = { question: message, stage: "질문을 보내는 중입니다" };
  $("wb-ask").value = "";
  $("wb-send").disabled = true;
  drawTimeline();
  try {
    // 답을 한 번에 기다리지 않고 흘려 받는다(141절) — 수십 초 동안 빈 말풍선 대신 단계와 쓰이는 중인 설명·SQL이 보인다
    const pending = state.pendingQuestion;
    const sentAt = performance.now();
    let reply = null;
    await streamEvents(`/api/workbench/worksheets/${state.sheet.id}/assistant/stream`, {
      body,
      onEvent: (name, data) => {
        if (name === "stage") {
          pending.stage = data.text;
        } else if (name === "partial") {
          if (!pending.firstPartialMs && (data.explanation || data.sql)) pending.firstPartialMs = performance.now() - sentAt;
          Object.assign(pending, { explanation: data.explanation, sql: data.sql, stage: "AI가 답을 쓰는 중입니다" });
        } else if (name === "reply") {
          reply = data;
          return;
        } else if (name === "error") {
          throw new ApiError(data.status, { error: data.message });
        }
        updatePending($("wb-timeline"), pending);
      },
    });
    if (!reply) throw new Error("AI 응답이 끝까지 오지 않았습니다(연결 끊김)");
    const first = pending.firstPartialMs ? ` · 첫 글자 ${Math.round(pending.firstPartialMs / 100) / 10}초` : "";
    $("wb-backend").textContent = reply.aiEnabled ? `${reply.backend} · ${Math.round(reply.elapsedMs / 100) / 10}초${first}` : "AI 꺼짐";
    if (reply.note) $("wb-share-note").textContent = reply.note;
    state.chips = [];
    state.lastFailure = null;
    drawChips();
  } catch (e) {
    $("wb-share-note").textContent = `AI 요청 실패: ${e.message}`;
  } finally {
    state.pendingQuestion = null;
    $("wb-send").disabled = false;
    setPicking(false);
    await reloadSheets();
    loadTimeline();
  }
}

// ---------- 내보내기·기록·탭 ----------

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
    const a = document.createElement("a");
    a.href = URL.createObjectURL(blob);
    a.download = encoded ? decodeURIComponent(encoded[1]) : "workbench.csv";
    a.click();
    URL.revokeObjectURL(a.href);
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
        <td class="muted">${esc(localTime(h.occurredAt, { seconds: true }))}</td>
        <td><span class="outcome ${esc(h.outcome)}">${esc(h.outcome)}</span> ${esc(h.action)}</td>
        <td class="muted">${esc(h.kind)}${h.rowCount !== null ? ` · ${esc(h.rowCount)}행` : ""}</td>
        <td><code>${esc(h.statement.slice(0, 300))}</code>${h.error ? `<div class="muted">${esc(h.error.slice(0, 200))}</div>` : ""}</td>
      </tr>`).join("")}</tbody></table>
      <p class="hint">기록에는 리터럴 값이 가려진 문장이 남습니다. 행을 누르면 편집기로 가져옵니다.</p>`;
    box.onclick = (e) => {
      const row = e.target.closest("tr[data-i]");
      if (row) { editor.value = items[Number(row.dataset.i)].statement; onEdit(); }
    };
  } catch (e) {
    box.textContent = `기록을 불러오지 못했습니다: ${e.message}`;
  }
}

function showPane(name) {
  document.querySelectorAll(".wb-rtab").forEach((b) => b.classList.toggle("active", b.dataset.pane === name));
  $("wb-pane-grid").hidden = name !== "grid";
  $("wb-pane-compare").hidden = name !== "compare";
  $("wb-pane-table").hidden = name !== "table";
  $("wb-pane-history").hidden = name !== "history";
}

function showChatPane(name) {
  document.querySelectorAll(".wb-ctab").forEach((b) => b.classList.toggle("active", b.dataset.cpane === name));
  $("wb-cpane-chat").hidden = name !== "chat";
  $("wb-cpane-schema").hidden = name !== "schema";
  $("wb-cpane-tickets").hidden = name !== "tickets";
}

// ---------- 변경 요청·인스턴스 간 비교 ----------

function openTicket(sql, reason = "") {
  if (!state.instance || !sql || !sql.trim()) return;
  $("wb-ticket-sql").textContent = sql;
  $("wb-ticket-reason").value = reason;
  $("wb-ticket-verify").value = "";
  $("wb-ticket-error").hidden = true;
  $("wb-ticket-ok").disabled = false;
  $("wb-ticket-modal").hidden = false;
  $("wb-ticket-reason").focus();
}

async function submitTicket() {
  const ok = $("wb-ticket-ok");
  ok.disabled = true;
  ok.textContent = "올리는 중(규칙 판정·AI 소견)...";
  try {
    const created = await request(`/api/instances/${encodeURIComponent(state.instance.id)}/reviews`, {
      method: "POST",
      body: { sql: $("wb-ticket-sql").textContent, reason: $("wb-ticket-reason").value.trim(), verifySql: $("wb-ticket-verify").value.trim() || null },
    });
    $("wb-ticket-modal").hidden = true;
    showChatPane("tickets");
    await tickets.load(state.instance.id, created.id);
  } catch (e) {
    $("wb-ticket-error").textContent = `요청을 올리지 못했습니다: ${e.message}`;
    $("wb-ticket-error").hidden = false;
  } finally {
    ok.disabled = false;
    ok.textContent = "요청 올리기";
  }
}

function drawCompareTargets() {
  $("wb-cmp-left").textContent = state.instance ? `${state.instance.name} (${state.instance.type})` : "";
  const others = state.instances.filter((i) => state.instance && i.id !== state.instance.id);
  $("wb-cmp-right").innerHTML = others.length
    ? others.map((i) => `<option value="${esc(i.id)}">${esc(i.name)} · ${esc(i.type)}${i.readConfigured ? "" : " (조회 계정 없음)"}</option>`).join("")
    : '<option value="">비교할 다른 인스턴스가 없습니다</option>';
}

async function runCompare() {
  const sql = editor.statementToRun();
  const right = $("wb-cmp-right").value;
  if (!state.instance || !sql.trim() || !right) return;
  const keyColumns = $("wb-cmp-keys").value.split(",").map((k) => k.trim()).filter(Boolean);
  const box = $("wb-compare");
  box.className = "";
  box.innerHTML = '<div class="muted">두 인스턴스에서 실행하는 중...</div>';
  $("wb-cmp-run").disabled = true;
  try {
    const res = await request("/api/workbench/compare", {
      method: "POST",
      body: { leftInstanceId: state.instance.id, rightInstanceId: Number(right), sql, keyColumns, rowLimit: Number($("wb-limit").value) },
    });
    const side = (s) => `<span><b>${esc(s.name)}</b> <span class="muted">${esc(s.type)} · ${esc(s.rowCount)}행${s.truncated ? "+" : ""} · ${esc(s.elapsedMs)}ms</span></span>`;
    box.innerHTML = `<div class="cmp-sides">${side(res.left)}<span class="muted">대</span>${side(res.right)}</div>`
      + renderDiff(res.diff, {
        leftLabel: res.left.name, rightLabel: res.right.name,
        addedLabel: `${res.right.name}에만`, removedLabel: `${res.left.name}에만`, maskedColumns: res.maskedColumns,
      });
  } catch (e) {
    box.innerHTML = `<div class="wb-msg error"><strong>비교하지 못했습니다${e instanceof ApiError ? " (" + esc(e.status) + ")" : ""}</strong><p>${esc(e.message)}</p></div>`;
  } finally {
    $("wb-cmp-run").disabled = false;
    loadHistory();
  }
}
