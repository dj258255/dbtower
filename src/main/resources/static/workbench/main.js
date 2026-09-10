// 거버넌스 SQL 워크벤치 — 인스턴스·워크시트·편집기·결과 미리보기·AI 대화를 잇는다.
// 정책(분류·계정·읽기 전용·마스킹·기록)과 판정은 전부 서버가 강제한다. 이 화면은 결과를 보여줄 뿐 판정하지 않는다.

import { request, esc, csrfToken, ApiError } from "./api.js";
import { SqlEditor } from "./editor.js";
import { renderTree } from "./schema-tree.js";
import { renderGrid } from "./grid.js";
import { renderTimeline, renderChips } from "./chat.js";

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
};

const editor = new SqlEditor({
  input: $("wb-input"),
  highlighter: $("wb-highlight"),
  complete: $("wb-complete"),
  onRun: () => run(),
  onChange: onEdit,
});

init();

async function init() {
  bindChrome();
  try {
    state.me = await request("/api/me");
    $("user-chip").textContent = `${state.me.username} · ${state.me.role}`;
  } catch {
    // 표시만 생략한다 — 권한 판정은 서버가 한다
  }
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
  if (select.value) selectInstance(select.value, params.get("sheet"));
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

async function createSheet() {
  const sheet = await request(`/api/workbench/instances/${state.instance.id}/worksheets`, { method: "POST", body: { title: "" } });
  await reloadSheets();
  openSheet(sheet.id);
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
  });
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
  const c = e instanceof ApiError ? e.body.classification : null;
  if (e instanceof ApiError && e.status === 409 && c && c.tier === "NEEDS_APPROVAL") {
    body = `<div class="wb-msg change"><strong>변경 요청이 필요한 문장입니다 (${esc(c.kind)})</strong>
      <p>${esc(c.reason)}</p><p class="muted">워크벤치는 조회만 즉시 실행합니다. 데이터·구조 변경은 승인 티켓으로 올려 변경 계정으로 실행합니다.</p>`;
  } else if (e instanceof ApiError && e.status === 400 && c) {
    body = `<div class="wb-msg blocked"><strong>차단된 문장입니다 (${esc(c.kind)})</strong><p>${esc(c.reason)}</p>`;
  } else if (e instanceof ApiError && e.status === 422) {
    body = `<div class="wb-msg error"><strong>대상 DB가 문장을 거부했습니다</strong><pre>${esc(e.message)}</pre>`;
  } else {
    body = `<div class="wb-msg error"><strong>실행하지 않았습니다${e instanceof ApiError ? " (" + esc(e.status) + ")" : ""}</strong><p>${esc(e.message)}</p>`;
  }
  overlay.innerHTML = `${body}<div class="wb-overlay-actions">
      <button class="btn btn-small" data-overlay="fix">AI로 고치기</button>
      <button class="btn btn-small" data-overlay="close">닫기</button></div></div>`;
  overlay.hidden = false;
  overlay.onclick = (ev) => {
    const b = ev.target.closest("[data-overlay]");
    if (!b) return;
    if (b.dataset.overlay === "close") hideOverlay();
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
  state.pendingQuestion = message;
  $("wb-ask").value = "";
  $("wb-send").disabled = true;
  drawTimeline();
  try {
    const reply = await request(`/api/workbench/worksheets/${state.sheet.id}/assistant`, { method: "POST", body });
    $("wb-backend").textContent = reply.aiEnabled ? `${reply.backend} · ${Math.round(reply.elapsedMs / 100) / 10}초` : "AI 꺼짐";
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
        <td class="muted">${esc(String(h.occurredAt).replace("T", " ").slice(0, 19))}</td>
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
  $("wb-pane-history").hidden = name !== "history";
}

function showChatPane(name) {
  document.querySelectorAll(".wb-ctab").forEach((b) => b.classList.toggle("active", b.dataset.cpane === name));
  $("wb-cpane-chat").hidden = name !== "chat";
  $("wb-cpane-schema").hidden = name !== "schema";
}
