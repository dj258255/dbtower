// 거버넌스 SQL 워크벤치 — 인스턴스·워크시트·편집기·결과 미리보기·AI 대화를 잇는다.
// 정책(분류·계정·읽기 전용·마스킹·기록)과 판정은 전부 서버가 강제한다. 이 화면은 결과를 보여줄 뿐 판정하지 않는다.

import { request, streamEvents, esc, csrfToken, ApiError, localTime } from "./api.js";
import { SqlEditor, highlight } from "./editor.js";
import { renderTree } from "./schema-tree.js";
import { renderGrid } from "./grid.js";
import { renderTimeline, renderChips, updatePending } from "./chat.js";
import { renderDiff } from "./diff.js";
import { TicketPanel } from "./tickets.js";
import { renderTableDetail } from "./table-detail.js";

const $ = (id) => document.getElementById(id);

// 인스턴스 고르기도 관제와 같은 드롭다운을 쓴다(B5). app.js가 window로 하나만 내보낸 것을 그대로 쓴다 —
// 복제하면 두 화면의 드롭다운이 서로 다르게 늙는다. 없으면(다른 화면에 단독으로 붙였을 때) 네이티브 select로 남는다
const enhanceSelect = (sel) => window.dbtowerEnhanceSelect?.(sel);

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
  schemaError: null,
  expanded: new Set(),
  sheets: [],
  sheet: null,
  sheetError: null,
  archiveConfirm: null,
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
  detailSeq: 0,
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
  onOpenSql: (sql) => { editor.value = sql; onEdit(); showPane("grid"); editor.focus(); },
  onProposeTicket: (sql, reason) => openTicket(sql, reason),
});

// 한 셸의 워크벤치 모드(149절) — 셸(app.js setMode)이 처음 워크벤치로 들어올 때 mount를 부른다. 두 번 불러도 한 번만 시작한다
let mounted = null;

export function mount() {
  if (!mounted) mounted = init();
  return mounted;
}

/** 이미 붙은 워크벤치가 관제에서 넘김(쿼리 상세·인덱스 제안)을 받는다 — 인스턴스를 맞추고 넘김을 연다 */
export async function navigate({ instance = null, handoff = null } = {}) {
  await mount();
  const select = $("wb-instance");
  if (instance != null && String(instance) !== select.value && state.instances.some((i) => String(i.id) === String(instance))) {
    select.value = String(instance);
    await selectInstance(select.value, null);
  }
  const h = takeHandoff(handoff);
  // 요청한 인스턴스가 실제로 열렸을 때만 받는다 — 팀 범위 밖이라 다른 인스턴스가 열려 있으면 엉뚱한 대상에 SQL이 채워진다
  if (h && (instance == null || String(instance) === select.value)) await receiveHandoff(h);
}

/** 관제로 돌아갈 때 같은 인스턴스를 열도록 셸에 알려준다 */
export function currentInstanceId() {
  return state.instance ? state.instance.id : null;
}

async function init() {
  bindChrome();
  try {
    state.me = await request("/api/me");
  } catch {
    // 권한 판정은 서버가 한다
  }
  if (state.me && !can("WORKBENCH")) {
    // 셸이 먼저 막는다(app.js showWorkbenchGuard). 여기까지 오면 아무것도 시작하지 않는다
    return;
  }
  const handoff = takeHandoff(new URLSearchParams(location.search).get("handoff"));
  try {
    state.instances = await request("/api/workbench/instances");
  } catch (e) {
    // 실패를 워크시트 자리에 쓰면 사용자가 보는 인스턴스 자리는 비어 있다(사용자 지적) — 원인이 보이는 곳에 적는다
    showInstanceProblem(`인스턴스 목록을 불러오지 못했습니다: ${esc(e.message)}`);
    return;
  }
  const select = $("wb-instance");
  // 항목 글자는 이름과 조회 계정 유무, 기종은 아이콘(data-icon)으로 — 관제 인스턴스 카드와 같은 표기(B5)
  select.innerHTML = state.instances.length
    ? state.instances.map((i) =>
      `<option value="${esc(i.id)}" data-icon="${esc(i.type)}">${esc(i.name)}${i.readConfigured ? "" : " · 조회 계정 없음"}</option>`).join("")
    : '<option value="">볼 수 있는 인스턴스가 없습니다</option>';
  if (!state.instances.length) {
    // 역할에 따라 할 수 있는 일이 다르다 — 등록은 ADMIN 몫이라 요청자에게 "등록하세요"라고 하면 안 된다(B6).
    // 스키마 자리는 비운다: 같은 뜻을 두 곳에 쓰지 않고, 여기서 더 말할 것도 없다
    const admin = state.me && state.me.role === "ADMIN";
    showInstanceProblem(admin
      ? "볼 수 있는 인스턴스가 없습니다 — 다른 팀 대상이거나 아직 등록되지 않았습니다. 등록은 콘솔이 아니라 REST API(POST /api/instances)로 합니다."
      : "볼 수 있는 인스턴스가 없습니다 — 다른 팀 대상이거나 아직 등록되지 않았습니다. ADMIN에게 등록을 요청하세요.");
    $("wb-tree").textContent = "";
    return;
  }
  enhanceSelect(select);
  const params = new URLSearchParams(location.search);
  const wanted = params.get("instance");
  if (wanted && state.instances.some((i) => String(i.id) === wanted)) select.value = wanted;
  select._csSync?.();   // 값이 정해진 뒤 버튼 글자를 맞춘다(드롭다운은 select를 감싼 뒤 자동으로 한 번 맞춘다)
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
  // 로그아웃·사용자 표시는 셸(app.js loadMe)이 한 번만 건다 — 같은 버튼에 두 번 걸면 로그아웃 요청이 두 번 나간다
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
  // 툴바의 행 상한도 관제와 같은 드롭다운으로(B6) — 버튼 모양은 툴바 규칙(알약·같은 높이)이 덮는다
  enhanceSelect($("wb-limit"));
  bindInfoTips();
}

/**
 * 늘 떠 있던 설명 줄을 정보 아이콘 툴팁으로 옮긴다(B5). 마우스를 올릴 때와 키보드 초점일 때 둘 다 뜬다 —
 * hover만 있는 툴팁은 키보드 사용자에게 없는 정보가 된다(B2의 데이터 보호 줄과 같은 규칙).
 */
function bindInfoTips() {
  document.querySelectorAll("[data-info]").forEach((trigger) => {
    const tip = $(trigger.dataset.info);
    if (!tip) return;
    const show = () => { tip.hidden = false; trigger.setAttribute("aria-expanded", "true"); };
    const hide = () => { tip.hidden = true; trigger.setAttribute("aria-expanded", "false"); };
    trigger.addEventListener("mouseenter", show);
    trigger.addEventListener("mouseleave", hide);
    trigger.addEventListener("focus", show);
    trigger.addEventListener("blur", hide);
    trigger.addEventListener("keydown", (e) => { if (e.key === "Escape") hide(); });
  });
}

/** 인스턴스 자리에 문제를 드러낸다 — 여기가 사용자가 보는 자리다(워크시트 쪽이 아니라) */
function showInstanceProblem(message) {
  const note = $("wb-instance-note");
  note.hidden = false;
  note.className = "wb-note wb-note-error";
  note.textContent = message;
}

// ---------- 인스턴스·워크시트 ----------

async function selectInstance(id, sheetId) {
  state.instance = state.instances.find((i) => String(i.id) === String(id));
  if (!state.instance) return;
  const note = $("wb-instance-note");
  note.className = "wb-note";
  note.hidden = state.instance.readConfigured;
  note.textContent = "이 인스턴스에는 조회 계정(READ)이 없어 실행이 거부됩니다. ADMIN이 콘솔 계정을 등록해야 합니다.";

  // 응답이 늦게 오는 동안 다른 인스턴스를 고르면 앞 인스턴스의 스키마·설정·워크시트가 새 화면을 덮었다(148절 감사).
  // 도착했을 때 아직 이 인스턴스가 선택돼 있을 때만 쓴다 — 실시간 세션(onLiveFrame)과 같은 규칙
  const inst = state.instance;
  const current = () => state.instance === inst;

  state.schema = null;
  state.schemaError = null;
  state.expanded = new Set();
  $("wb-tree").textContent = "스키마를 불러오는 중...";
  request(`/api/instances/${encodeURIComponent(id)}/schema`)
    .then((schema) => { if (current()) { state.schema = schema; editor.setSchema(schema); } })
    // 실패를 state.schema=null로만 두면 트리가 "불러오지 못했습니다"만 말하고 사유를 감춘다(B5)
    .catch((e) => { if (current()) { state.schema = null; state.schemaError = e.message; } })
    .finally(() => {
      if (!current()) return;
      drawTree();
      $("wb-schema-count").textContent = state.schema ? state.schema.tables.length : "";
    });
  request(`/api/workbench/instances/${encodeURIComponent(id)}/settings`)
    .then((s) => { if (current()) { state.allowValues = s.allowAiResultValues; drawShare(); } })
    .catch(() => { if (current()) { state.allowValues = false; drawShare(); } });
  drawCompareTargets();
  const wantedTicket = state.pendingTicket;
  state.pendingTicket = null;
  tickets.load(id, wantedTicket).then(() => { if (wantedTicket && current()) showPane("tickets"); });

  // 워크시트 요청이 실패하면 편집기·탭이 이전 인스턴스 그대로 멈춰 있었다(사용자 지적) — 사유를 탭 줄에 적는다
  state.sheets = [];
  state.sheet = null;
  state.sheetError = null;
  drawSheets();
  let sheets;
  try {
    sheets = await request(`/api/workbench/instances/${encodeURIComponent(id)}/worksheets`);
  } catch (e) {
    if (current()) {
      state.sheetError = `워크시트를 불러오지 못했습니다: ${e.message}`;
      drawSheets();
    }
    return;
  }
  if (!current()) return;
  state.sheets = sheets;
  if (!state.sheets.length) {
    await createSheet();
    return;
  }
  const target = state.sheets.find((s) => String(s.id) === String(sheetId)) || state.sheets[0];
  openSheet(target.id);
}

/**
 * 워크시트 탭 (B5) — 워크시트는 "지금 편집 중인 문서"라 편집기 바로 위에 붙는다(DBeaver·DataGrip의 에디터 탭).
 * 왼쪽 목록이던 것을 옮겼다: 왼쪽은 스키마 트리가 끝까지 쓴다. 넘치면 이 줄 안에서 가로로 스크롤한다.
 * ×는 보관이고 그 자리에서 한 번 더 묻는다(되돌릴 수 없는 요청을 한 번의 오조작으로 보내지 않는다).
 */
function drawSheets() {
  const box = $("wb-sheets");
  // 탭 안에 넣어 둔 두 요소를 먼저 탭 줄 밖으로 빼낸다 — 아래 innerHTML 교체가 탭과 함께 DOM에서 지워 버린다.
  // (#wb-version·#wb-title은 id를 유지해야 해서 innerHTML로 새로 만들 수 없다)
  const bar = document.querySelector(".wb-tabs");
  const version = $("wb-version");
  const title = $("wb-title");
  bar.appendChild(version);
  bar.appendChild(title);
  version.hidden = true;
  title.hidden = true;
  if (state.sheetError) {
    box.innerHTML = `<span class="wb-msg error">${esc(state.sheetError)}</span>`;
    return;
  }
  box.innerHTML = state.sheets.map((s) => {
    if (state.archiveConfirm === s.id) {
      return `<span class="wb-sheet-tab wb-tab-confirm" data-id="${esc(s.id)}">
        <span>보관할까요?</span>
        <button type="button" class="wb-tab-yes" data-archive-yes="${esc(s.id)}">보관</button>
        <button type="button" class="wb-tab-no" data-archive-no="1">취소</button>
      </span>`;
    }
    const active = state.sheet && state.sheet.id === s.id;
    return `<span class="wb-sheet-tab${active ? " active" : ""}" data-id="${esc(s.id)}" role="tab" tabindex="0" aria-selected="${active}">
      <span class="wb-tab-name">${esc(s.title)}</span>
      <button type="button" class="wb-tab-x" data-archive="${esc(s.id)}" title="보관" aria-label="${esc(s.title)} 보관">×</button>
    </span>`;
  }).join("") || '<span class="wb-tabs-empty muted">워크시트가 없습니다</span>';

  // 버전 표시는 활성 탭 안 작은 글자다 — id를 유지한 채 자리만 옮긴다(이름 입력도 같은 방식, 더블클릭 때만)
  const activeTab = box.querySelector(".wb-sheet-tab.active");
  if (activeTab) {
    activeTab.insertBefore(version, activeTab.querySelector(".wb-tab-x"));
    version.hidden = !version.textContent;
  }

  box.onclick = async (e) => {
    const yes = e.target.closest("[data-archive-yes]");
    if (yes) { state.archiveConfirm = null; await archiveSheet(yes.dataset.archiveYes); return; }
    if (e.target.closest("[data-archive-no]")) { state.archiveConfirm = null; drawSheets(); return; }
    const x = e.target.closest("[data-archive]");
    // dataset 값은 문자열이다 — 탭의 id(숫자)와 === 로 비교하면 영영 맞지 않아 확인 상자가 안 뜬다
    if (x) { state.archiveConfirm = Number(x.dataset.archive); drawSheets(); return; }
    const tab = e.target.closest(".wb-sheet-tab[data-id]");
    // 이미 활성인 탭은 다시 그리지 않는다 — 다시 열면 편집기 내용을 되돌리고, 무엇보다 더블클릭(이름 바꾸기)이
    // 첫 클릭의 재렌더로 대상이 바뀌어 아예 성립하지 않는다
    if (tab && !tab.classList.contains("active")) openSheet(Number(tab.dataset.id));
  };
  // 활성 탭 더블클릭 = 그 자리에서 이름 바꾸기
  box.ondblclick = (e) => { if (e.target.closest(".wb-sheet-tab.active[data-id]")) startRename(); };
}

async function archiveSheet(id) {
  try {
    await request(`/api/workbench/worksheets/${encodeURIComponent(id)}`, { method: "DELETE" });
  } catch (e) {
    state.sheetError = `보관하지 못했습니다: ${e.message}`;
    drawSheets();
    return;
  }
  await reloadSheets();
  if (state.sheet && String(state.sheet.id) === String(id)) {
    state.sheets.length ? openSheet(state.sheets[0].id) : createSheet();
  }
}

/** 활성 탭의 제목이 그 자리에서 입력으로 바뀐다(#wb-title은 원래 자리로 돌아간다) */
function startRename() {
  const tab = $("wb-sheets").querySelector(".wb-sheet-tab.active");
  if (!tab || !state.sheet) return;
  const input = $("wb-title");
  const name = tab.querySelector(".wb-tab-name");
  if (!name) return;
  input.value = state.sheet.title;
  input.hidden = false;
  name.hidden = true;
  tab.insertBefore(input, name);
  input.focus();
  input.select();
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
  // 모드를 주소에 남긴다 — 새로고침·공유한 주소가 관제가 아니라 이 워크시트로 돌아오게(149절)
  history.replaceState(null, "", `/?mode=workbench&instance=${encodeURIComponent(state.instance.id)}&sheet=${encodeURIComponent(id)}`);
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
  const input = $("wb-title");
  const title = input.value.trim();
  // 입력을 탭 밖 원래 자리로 돌려놓는다 — 다음 drawSheets가 탭을 새로 그린다
  input.hidden = true;
  document.querySelector(".wb-tabs").appendChild(input);
  if (!state.sheet || !title || title === state.sheet.title) return;
  const previous = state.sheet.title;
  state.sheet.title = title;   // 화면을 먼저 바꾸고, 실패하면 되돌린다
  try {
    await request(`/api/workbench/worksheets/${state.sheet.id}`, { method: "PATCH", body: { title } });
  } catch (e) {
    state.sheet.title = previous;
    state.sheetError = `이름을 바꾸지 못했습니다: ${e.message}`;
  }
  await reloadSheets();
  if (state.sheetError) { drawSheets(); state.sheetError = null; }
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
    error: state.schemaError,
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

// 행 수·크기·인덱스·키·DDL은 모니터 계정의 카탈로그 조회(테이블 상세 API)로, 열 목록은 이미 받은 스키마 트리로 채운다.
// 외래키 링크로 테이블을 빠르게 옮겨 다니면 늦게 온 앞 응답이 뒤 화면을 덮을 수 있어 요청 번호로 거른다
async function openTableDetail(name) {
  if (!state.instance) return;
  const seq = ++state.detailSeq;
  const instanceId = state.instance.id;
  showPane("table");
  const box = $("wb-table");
  box.className = "";
  box.innerHTML = `<div class="muted">${esc(name)} 상세를 불러오는 중...</div>`;
  let d;
  try {
    d = await request(`/api/instances/${encodeURIComponent(instanceId)}/table-detail`, { method: "POST", body: { table: name } });
  } catch (e) {
    if (seq !== state.detailSeq) return;
    box.innerHTML = `<div class="wb-msg error"><strong>상세를 불러오지 못했습니다</strong><p>${esc(e.message)}</p></div>`;
    return;
  }
  if (seq !== state.detailSeq || !state.instance || state.instance.id !== instanceId) return;
  const tables = state.schema ? state.schema.tables : [];
  const table = tables.find((t) => t.name === name);
  // 참조 테이블은 스키마 트리에 있는 테이블일 때만 누를 수 있다(다른 스키마·상한 밖 테이블과 뷰는 상세 API가 받지 않는다)
  const known = new Map(tables.filter((t) => t.kind !== "VIEW").map((t) => [t.name.toLowerCase(), t.name]));
  const preview = (PREVIEW[state.instance.type] || PREVIEW.MYSQL)(name);
  box.innerHTML = renderTableDetail(d, {
    title: d.table || name,
    columns: table ? table.columns : null,
    pickColumns: true,
    canOpen: (t) => known.has(t.toLowerCase()) && t.toLowerCase() !== name.toLowerCase(),
    actions: `<button class="btn btn-small" data-td="preview">미리보기 실행</button>
      <button class="btn btn-small" data-td="editor">SELECT 편집기로</button>
      <button class="btn btn-small" data-td="chip">채팅에 붙이기</button>`,
  });
  box.onclick = (e) => {
    const open = e.target.closest("[data-open-table]");
    if (open) { openTableDetail(known.get(open.dataset.openTable.toLowerCase()) || open.dataset.openTable); return; }
    const col = e.target.closest("[data-col]");
    if (col) { addChip({ type: "column", value: `${name}.${col.dataset.col}` }); revealChat(); return; }
    const b = e.target.closest("[data-td]");
    if (!b) return;
    if (b.dataset.td === "preview") { showPane("grid"); previewSql(preview); }
    if (b.dataset.td === "editor") { editor.value = preview; onEdit(); editor.focus(); }
    if (b.dataset.td === "chip") { addChip({ type: "table", value: name }); revealChat(); }
  };
}

function setPicking(on) {
  state.picking = on;
  const btn = $("wb-pick");
  btn.setAttribute("aria-pressed", String(on));
  // 켜짐은 주 버튼 색이다 — 색은 style.css의 .btn-primary 한 곳에만 있다(여기서 다시 칠하지 않는다)
  btn.classList.toggle("btn-primary", on);
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
      revealChat();
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
    // 흘려 받는 중인 답은 질문을 보낸 워크시트에만 그린다 — 다른 워크시트를 열면 남의 말풍선이 붙었다(148절 감사)
    pending: state.pendingQuestion && state.sheet && state.pendingQuestion.sheetId === state.sheet.id ? state.pendingQuestion : null,
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
  const sentSheetId = state.sheet.id;
  const onSentSheet = () => Boolean(state.sheet && state.sheet.id === sentSheetId);
  state.pendingQuestion = { question: message, stage: "질문을 보내는 중입니다", sheetId: sentSheetId };
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
        if (onSentSheet()) updatePending($("wb-timeline"), pending);
      },
    });
    if (!reply) throw new Error("AI 응답이 끝까지 오지 않았습니다(연결 끊김)");
    const first = pending.firstPartialMs ? ` · 첫 글자 ${Math.round(pending.firstPartialMs / 100) / 10}초` : "";
    $("wb-backend").textContent = reply.aiEnabled ? `${reply.backend} · ${Math.round(reply.elapsedMs / 100) / 10}초${first}` : "AI 꺼짐";
    if (reply.note) $("wb-share-note").textContent = reply.note;
    // 기다리는 동안 다른 워크시트로 옮겼으면 그 워크시트에서 붙인 칩·실패 문맥을 지우지 않는다
    if (onSentSheet()) {
      state.chips = [];
      state.lastFailure = null;
      drawChips();
    }
  } catch (e) {
    $("wb-share-note").textContent = `AI 요청 실패: ${e.message}`;
  } finally {
    state.pendingQuestion = null;
    $("wb-send").disabled = false;
    if (onSentSheet()) setPicking(false);
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

const PANES = ["grid", "table", "compare", "tickets", "history"];

function showPane(name) {
  document.querySelectorAll(".wb-rtab").forEach((b) => b.classList.toggle("active", b.dataset.pane === name));
  PANES.forEach((p) => { $(`wb-pane-${p}`).hidden = p !== name; });
  // 티켓을 검토할 때는 편집기를 줄여 전후 비교·실행 기록에 높이를 준다 — 편집기로 가져오기를 누르면 결과 탭으로 돌아오며 다시 펼쳐진다
  $("wb-main").classList.toggle("is-reviewing", name === "tickets");
}

// 채팅은 늘 보이는 칸이라 탭을 바꿀 필요가 없다. 한 열로 접힌 좁은 화면에서만 아래에 있으니 입력창까지 스크롤한다
function revealChat() {
  $("wb-ask").scrollIntoView({ block: "nearest", behavior: "smooth" });
}

// ---------- 변경 요청·인스턴스 간 비교 ----------

function openTicket(sql, reason = "") {
  if (!state.instance || !sql || !sql.trim()) return;
  $("wb-ticket-sql").textContent = sql;
  $("wb-ticket-reason").value = reason;
  $("wb-ticket-verify").value = "";
  $("wb-ticket-error").hidden = true;
  $("wb-ticket-progress").hidden = true;
  $("wb-ticket-progress").innerHTML = "";
  $("wb-ticket-ok").disabled = false;
  $("wb-ticket-modal").hidden = false;
  $("wb-ticket-reason").focus();
}

// 흘려 받는다(146절) — 규칙 판정은 몇 ms면 끝나는데 AI 소견을 기다리느라 버튼이 수십 초 "올리는 중"이었다.
// 규칙 지적을 먼저 보이고 AI 소견을 쓰이는 대로 이어 붙인다. 티켓에 남는 소견은 서버가 완성본으로 저장한 것이다
async function submitTicket() {
  const ok = $("wb-ticket-ok");
  const progress = $("wb-ticket-progress");
  ok.disabled = true;
  ok.textContent = "규칙 판정 중...";
  progress.hidden = false;
  progress.innerHTML = '<div class="muted">규칙 판정 중...</div>';
  let opinion = "";
  try {
    let created = null;
    await streamEvents(`/api/instances/${encodeURIComponent(state.instance.id)}/reviews/stream`, {
      body: { sql: $("wb-ticket-sql").textContent, reason: $("wb-ticket-reason").value.trim(), verifySql: $("wb-ticket-verify").value.trim() || null },
      onEvent: (name, data) => {
        if (name === "findings") {
          ok.textContent = "AI 소견 작성 중...";
          progress.innerHTML = `<div class="wb-label">규칙 판정${data.parseLimited ? " (해석 한계 있음)" : ""}</div>
            <ul class="tk-findings">${data.findings.map((f) => `<li>${esc(f)}</li>`).join("")}</ul>
            <div class="wb-label">AI 1차 소견 <span class="muted">작성 중</span></div><div class="wb-ticket-opinion"></div>`;
        } else if (name === "text") {
          opinion += data.delta;
          const box = progress.querySelector(".wb-ticket-opinion");
          if (box) box.textContent = opinion;
        } else if (name === "created") {
          created = data;
        } else if (name === "error") {
          throw new ApiError(data.status, { error: data.message });
        }
      },
    });
    if (!created) throw new Error("티켓이 만들어졌는지 확인하지 못했습니다(연결 끊김) — 변경 티켓 목록을 새로고침해 보세요");
    $("wb-ticket-modal").hidden = true;
    showPane("tickets");
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
  const select = $("wb-cmp-right");
  select.innerHTML = others.length
    ? others.map((i) => `<option value="${esc(i.id)}" data-icon="${esc(i.type)}">${esc(i.name)}${i.readConfigured ? "" : " · 조회 계정 없음"}</option>`).join("")
    : '<option value="">비교할 다른 인스턴스가 없습니다</option>';
  // 항목을 다시 채운 뒤 관제와 같은 드롭다운의 버튼 글자를 맞춘다(B6) — 감싸기(enhanceSelect)는 처음 한 번
  enhanceSelect(select);
  select._csSync?.();
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
