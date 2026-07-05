// DBTower 웹 콘솔 — 프레임워크 없는 정적 SPA.
// 백엔드가 본질인 프로젝트라 프론트는 의존성 0으로 얇게 유지한다 (java -jar 하나로 화면까지).
// 화면 구도는 당근 KDMS Database Insight를 참고: 인스턴스 선택 -> 그래프 드래그로 구간 선택
// -> Top Query 증감(NEW 뱃지) -> 쿼리 클릭 -> 실행계획 + AI 분석.

const $ = (sel) => document.querySelector(sel);

// CSRF: 서버가 XSRF-TOKEN 쿠키로 준 토큰을 변경 요청 헤더로 되돌려준다 (A1)
const csrfToken = () => {
  const m = document.cookie.match(/XSRF-TOKEN=([^;]+)/);
  return m ? decodeURIComponent(m[1]) : "";
};

const api = (path, opts = {}) => {
  const headers = { ...(opts.headers || {}) };
  if (opts.method && opts.method !== "GET") headers["X-XSRF-TOKEN"] = csrfToken();
  return fetch(path, { ...opts, headers }).then((r) => {
    if (r.status === 401) { location.href = "/login.html"; throw new Error("로그인이 필요합니다"); }
    if (!r.ok) return r.text().then((t) => { throw new Error(`${r.status} ${t}`); });
    return r.json();
  });
};

const state = {
  instance: null,      // 선택된 인스턴스 {id, name, type, ...}
  instances: [],       // 등록된 인스턴스 전체 목록 (Schema Diff 드롭다운용)
  activity: [],        // [{time, qps, avgLatencyMs}]
  dragMode: null,      // 'target' | 'base'
  selections: {},      // {target: {from: Date, to: Date}, base: {...}}
  compareMode: false,  // 마지막 조회가 비교 조회였는지
  currentQuery: null,  // 상세 패널에 열린 쿼리
  lastPlan: null,      // 마지막 EXPLAIN 실행계획 (문의 첨부용)
  lastFindings: [],    // 마지막 규칙 기반 지적
  lastAi: null,        // 마지막 AI 분석
  role: null,          // 로그인 주체의 역할 (ADMIN이면 세션 kill 버튼 노출)
};

// ---------- 유틸 ----------
const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) =>
  ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));

// datetime-local 입력값(로컬 시각)과 LocalDateTime(ISO) 사이 변환
const toLocalInput = (date) => {
  const p = (n) => String(n).padStart(2, "0");
  return `${date.getFullYear()}-${p(date.getMonth() + 1)}-${p(date.getDate())}T${p(date.getHours())}:${p(date.getMinutes())}`;
};
const fmtNum = (v, digits = 2) => v == null ? "-" : Number(v).toLocaleString("ko-KR", { maximumFractionDigits: digits });

// 바이트를 사람이 읽는 단위로 (파티션 크기 등). 1024 진법, 소수 한 자리.
const fmtBytes = (v) => {
  if (v == null) return "-";
  let n = Number(v);
  const units = ["B", "KB", "MB", "GB", "TB"];
  let u = 0;
  while (n >= 1024 && u < units.length - 1) { n /= 1024; u += 1; }
  return `${u === 0 ? n : n.toFixed(1)} ${units[u]}`;
};

// 증감 셀: "target값 (▲ diff)" — KDMS 표기. changePct가 null(base 0)이면 화살표 생략
function deltaCell(base, target, changePct, digits = 2) {
  const t = fmtNum(target, digits);
  if (changePct == null) return `<span class="num">${t}</span>`;
  const diff = target - base;
  const cls = diff >= 0 ? "delta-up" : "delta-down";
  const arrow = diff >= 0 ? "▲" : "▼";
  return `<span class="num">${t} <span class="${cls}">(${arrow} ${fmtNum(Math.abs(diff), digits)})</span></span>`;
}

// ---------- 인스턴스 ----------
async function loadInstances() {
  const list = await api("/api/instances");
  state.instances = list;
  populateSchemaSelects(list); // Schema Diff 좌/우 드롭다운 채우기
  const box = $("#instance-list");
  if (!list.length) {
    box.innerHTML = '<div class="muted">등록된 인스턴스가 없습니다 — POST /api/instances 로 등록하세요.</div>';
    return;
  }
  box.innerHTML = list.map((i) => `
    <div class="instance-card" data-id="${i.id}">
      <div class="instance-name">
        <span class="type-badge type-${esc(i.type)}">${esc(i.type)}</span>
        ${esc(i.name)}
        <span class="health-dot" id="health-${i.id}"></span>
      </div>
      <div class="instance-host">${esc(i.host)}:${i.port} / ${esc(i.dbName)}
        <span class="health-ms" id="healthms-${i.id}"></span></div>
    </div>`).join("");

  box.querySelectorAll(".instance-card").forEach((card) => {
    card.addEventListener("click", () => selectInstance(list.find((i) => i.id == card.dataset.id), card));
  });
  // 첫 인스턴스 자동 선택 — 진입 즉시 대시보드가 차 있게
  const first = box.querySelector(".instance-card");
  if (first) selectInstance(list[0], first);
  // 헬스는 카드 렌더 후 비동기로 채운다 — 죽은 인스턴스가 목록 로딩을 막지 않게
  list.forEach(async (i) => {
    try {
      const h = await api(`/api/instances/${i.id}/health`);
      $(`#health-${i.id}`).classList.add(h.up ? "up" : "down");
      $(`#healthms-${i.id}`).textContent = h.up ? `${h.pingMillis}ms · ${h.version ?? ""}` : h.message;
    } catch { $(`#health-${i.id}`).classList.add("down"); }
  });
}

async function selectInstance(instance, card) {
  state.instance = instance;
  document.querySelectorAll(".instance-card").forEach((c) => c.classList.remove("selected"));
  card.classList.add("selected");
  $("#time-panel").hidden = false;
  $("#result-panel").hidden = false;

  // 기본 구간: 조회 = 최근 30분, 비교 = 그 직전 30분
  const now = new Date();
  $("#target-to").value = toLocalInput(now);
  $("#target-from").value = toLocalInput(new Date(now - 30 * 60000));
  $("#base-to").value = toLocalInput(new Date(now - 30 * 60000));
  $("#base-from").value = toLocalInput(new Date(now - 60 * 60000));
  state.selections = {};

  await Promise.all([loadActivity(), runQuery(), loadSlow(), loadReplication(), loadWaitEvents(), loadSessions(), loadLatencyPercentiles(), loadSloReport(), loadPartitions(), loadAdvisors(), loadFinOps(), loadAnomalies()]);
}

// ---------- Advisors (D2) — 자동 점검 결과를 심각도별로 표시 ----------
// 읽고 조언만 하는 진단이라 VIEWER도 조회 가능. 각 Advisor는 OK/위반/미지원/오류로 정직하게 표기한다.
const SEV_LABEL = { CRITICAL: "치명", WARNING: "경고", INFO: "정보" };
const STATUS_LABEL = { OK: "통과", VIOLATIONS: "지적", UNSUPPORTED: "미지원", ERROR: "오류" };

async function loadAdvisors() {
  const summary = $("#advisors-summary");
  const box = $("#advisors-result");
  summary.innerHTML = "";
  box.classList.add("muted");
  box.textContent = "점검 중...";
  let report;
  try {
    report = await api(`/api/instances/${state.instance.id}/advisors`);
  } catch (e) {
    box.textContent = `점검 실패: ${e.message}`;
    return;
  }
  box.classList.remove("muted");
  summary.innerHTML = `
    <span class="sev-badge sev-CRITICAL">치명 ${report.critical}</span>
    <span class="sev-badge sev-WARNING">경고 ${report.warning}</span>
    <span class="sev-badge sev-INFO">정보 ${report.info}</span>
    <span class="advisors-time muted">점검 ${esc(String(report.checkedAt).replace("T", " ").slice(0, 19))}</span>`;

  // 지적이 있는 Advisor를 먼저(나쁜 순), 그다음 통과/미지원 순으로 정렬한다.
  const order = { VIOLATIONS: 0, ERROR: 1, OK: 2, UNSUPPORTED: 3 };
  const checks = [...report.checks].sort((a, b) => (order[a.status] ?? 9) - (order[b.status] ?? 9));

  box.innerHTML = checks.map((c) => {
    const findings = (c.findings || []).map((f) => `
      <div class="advisor-finding sev-border-${esc(f.severity)}">
        <div class="advisor-finding-head">
          <span class="sev-badge sev-${esc(f.severity)}">${esc(SEV_LABEL[f.severity] ?? f.severity)}</span>
          <span class="advisor-finding-title">${esc(f.title)}</span>
        </div>
        <div class="advisor-finding-detail">${esc(f.detail)}</div>
        <div class="advisor-finding-reco"><strong>권고:</strong> ${esc(f.recommendation)}</div>
      </div>`).join("");
    const note = c.note && c.status !== "VIOLATIONS"
      ? `<div class="advisor-note muted">${esc(c.note)}</div>` : "";
    return `
      <div class="advisor-check status-${esc(c.status)}">
        <div class="advisor-check-head">
          <span class="advisor-status advisor-status-${esc(c.status)}">${esc(STATUS_LABEL[c.status] ?? c.status)}</span>
          <span class="advisor-check-title">${esc(c.title)}</span>
        </div>
        ${findings}${note}
      </div>`;
  }).join("");
}

// ---------- 비용/효율 FinOps (D6) — 낭비 후보를 종류별로, 신호까지만(절감액 산출 없음) ----------
// 미사용/중복 인덱스·큰 테이블·오버프로비저닝을 "낭비 후보"로 모은다. 대상 DB는 바꾸지 않는다(읽고 조언만).
// 사용 통계를 신뢰성 있게 못 얻는 기종(Oracle 미사용 인덱스 등)은 UNSUPPORTED로 정직하게 표기한다.
const FINOPS_STATUS_LABEL = { OK: "후보 없음", CANDIDATES: "후보", UNSUPPORTED: "미지원", ERROR: "오류" };
const WASTE_KIND_LABEL = {
  UNUSED_INDEX: "미사용 인덱스", REDUNDANT_INDEX: "중복·잉여 인덱스", LARGE_TABLE: "큰 테이블",
  OVER_INDEXED: "과다 인덱싱", CONNECTION_HEADROOM: "연결 여유", MEMORY_HEADROOM: "메모리 여유",
};

async function loadFinOps() {
  const summary = $("#finops-summary");
  const box = $("#finops-result");
  summary.innerHTML = "";
  box.classList.add("muted");
  box.textContent = "분석 중...";
  let report;
  try {
    report = await api(`/api/instances/${state.instance.id}/finops`);
  } catch (e) {
    box.textContent = `분석 실패: ${e.message}`;
    return;
  }
  box.classList.remove("muted");
  summary.innerHTML = `
    <span class="sev-badge sev-WARNING">낭비 후보 ${report.candidateCount}</span>
    <span class="advisors-time muted">신호까지만(절감액 산출 없음) · 분석 ${esc(String(report.generatedAt).replace("T", " ").slice(0, 19))}</span>`;

  // 후보가 있는 분석기를 먼저, 그다음 오류/후보없음/미지원 순으로 정렬한다.
  const order = { CANDIDATES: 0, ERROR: 1, OK: 2, UNSUPPORTED: 3 };
  const checks = [...report.checks].sort((a, b) => (order[a.status] ?? 9) - (order[b.status] ?? 9));

  box.innerHTML = checks.map((c) => {
    const cands = (c.candidates || []).map((w) => `
      <div class="advisor-finding sev-border-${esc(w.severity)}">
        <div class="advisor-finding-head">
          <span class="sev-badge sev-${esc(w.severity)}">${esc(SEV_LABEL[w.severity] ?? w.severity)}</span>
          <span class="finops-kind">${esc(WASTE_KIND_LABEL[w.kind] ?? w.kind)}</span>
          <span class="advisor-finding-title">${esc(w.target)}</span>
        </div>
        <div class="advisor-finding-detail">${esc(w.evidence)}</div>
        <div class="advisor-finding-reco"><strong>검토:</strong> ${esc(w.recommendation)}</div>
      </div>`).join("");
    const note = c.note && c.status !== "CANDIDATES"
      ? `<div class="advisor-note muted">${esc(c.note)}</div>` : "";
    return `
      <div class="advisor-check status-${esc(c.status)}">
        <div class="advisor-check-head">
          <span class="advisor-status advisor-status-${esc(c.status)}">${esc(FINOPS_STATUS_LABEL[c.status] ?? c.status)}</span>
          <span class="advisor-check-title">${esc(c.title)}</span>
        </div>
        ${cands}${note}
      </div>`;
  }).join("");
}

// ---------- 통합 헬스 스코어 (D8) — 흩어진 신호를 인스턴스별 한 점수로, 나쁜 순으로 ----------
// 인스턴스 선택과 무관한 함대 전체 뷰. "어디부터 볼지"를 서버가 정렬해 내려주고, 행 클릭 시 감점 사유를 분해한다.
const SCORE_SIGNAL_LABEL = {
  HEALTH: "가용성", ANOMALY: "이상 감지", ADVISOR: "Advisors", SLO: "SLO / 버짓", BACKUP: "백업 신선도",
};
const SCORE_STATE_LABEL = {
  OK: "정상", PENALIZED: "감점", INSUFFICIENT_DATA: "데이터 부족", ERROR: "수집 실패",
};

async function loadHealthScore() {
  const summary = $("#score-summary");
  const box = $("#score-result");
  let report;
  try {
    report = await api("/api/health-score");
  } catch (e) {
    box.classList.add("muted");
    box.textContent = `조회 실패: ${e.message}`;
    return;
  }
  box.classList.remove("muted");
  const g = report.gradeCounts || {};
  summary.innerHTML = `
    ${["A", "B", "C", "D", "F"].map((k) => `<span class="grade-badge grade-${k}">${k} ${g[k] ?? 0}</span>`).join("")}
    ${report.partialCount ? `<span class="score-partial">부분 데이터 ${report.partialCount}</span>` : ""}
    <span class="score-time muted">집계 ${esc(String(report.generatedAt).replace("T", " ").slice(0, 19))}</span>`;

  if (!report.instances.length) {
    box.innerHTML = '<div class="muted">등록된 인스턴스가 없습니다.</div>';
    return;
  }
  // 이미 서버가 나쁜 순으로 정렬해 내려준다 — 죽은 것·백업 없는 것이 위로 온다
  const rows = report.instances.map((s) => {
    // 주요 감점 사유: 감점 있는 신호만, 큰 순(서버가 이미 정렬). 없으면 정상 표기.
    const penalized = s.contributions.filter((c) => c.state === "PENALIZED");
    const reasons = penalized.length
      ? penalized.map((c) => `${SCORE_SIGNAL_LABEL[c.signal] ?? c.signal} −${fmtNum(c.penalty, 0)}`).join(" · ")
      : '<span class="muted">감점 없음</span>';
    // 행 클릭 시 펼칠 신호별 기여 분해(투명성) — 데이터 부족·수집 실패도 그대로 노출
    const detail = s.contributions.map((c) => `
      <div class="score-contrib score-state-${esc(c.state)}">
        <span class="score-contrib-signal">${esc(SCORE_SIGNAL_LABEL[c.signal] ?? c.signal)}</span>
        <span class="score-contrib-state score-state-badge-${esc(c.state)}">${esc(SCORE_STATE_LABEL[c.state] ?? c.state)}</span>
        <span class="score-contrib-penalty">${c.penalty > 0 ? `−${fmtNum(c.penalty, 0)}` : ""}</span>
        <span class="score-contrib-summary">${esc(c.summary)}</span>
      </div>`).join("");
    return `
      <tbody class="score-group" data-id="${s.instanceId}">
        <tr class="score-row score-grade-${esc(s.grade)}">
          <td><span class="type-badge type-${esc(s.type)}">${esc(s.type)}</span> ${esc(s.instanceName)}
            ${s.down ? '<span class="score-down">DOWN</span>' : ""}
            ${s.partial ? '<span class="score-partial-dot" title="일부 신호가 데이터 부족·수집 실패">부분</span>' : ""}</td>
          <td class="num score-num">${s.score}<span class="score-outof">/100</span></td>
          <td><span class="grade-badge grade-${esc(s.grade)}">${esc(s.grade)}</span></td>
          <td class="score-reasons">${reasons}</td>
        </tr>
        <tr class="score-detail-row" hidden><td colspan="4"><div class="score-detail">${detail}</div></td></tr>
      </tbody>`;
  }).join("");
  box.innerHTML = `
    <div class="table-scroll">
      <table class="qtable score-table">
        <thead><tr><th>인스턴스</th><th>점수</th><th>등급</th><th>주요 감점 사유 (클릭 시 분해)</th></tr></thead>
        ${rows}
      </table>
    </div>`;
  // 행 클릭 → 신호별 기여 분해 토글
  box.querySelectorAll(".score-row").forEach((row) => {
    row.addEventListener("click", () => {
      const detailRow = row.parentElement.querySelector(".score-detail-row");
      detailRow.hidden = !detailRow.hidden;
      row.classList.toggle("score-row-open", !detailRow.hidden);
    });
  });
}

// ---------- 백업 신선도 (D7) — 전 인스턴스를 한 표로, 오래된 것/백업 없는 것을 강조 ----------
// 인스턴스 선택과 무관한 함대 전체 뷰. "백업했다"가 아니라 "지금 최신이고 복원되는가"를 상시 비춘다.
const FRESHNESS_LABEL = { FRESH: "신선", STALE: "오래됨", NO_BACKUP: "백업 없음" };

async function loadBackupFreshness() {
  const summary = $("#freshness-summary");
  const box = $("#freshness-result");
  let report;
  try {
    report = await api("/api/backup-freshness");
  } catch (e) {
    box.classList.add("muted");
    box.textContent = `조회 실패: ${e.message}`;
    return;
  }
  box.classList.remove("muted");
  summary.innerHTML = `
    <span class="fresh-badge fresh-FRESH">신선 ${report.freshCount}</span>
    <span class="fresh-badge fresh-STALE">오래됨 ${report.staleCount}</span>
    <span class="fresh-badge fresh-NO_BACKUP">백업 없음 ${report.noBackupCount}</span>
    <span class="freshness-time muted">임계 ${report.thresholdHours}h · 집계 ${esc(String(report.checkedAt).replace("T", " ").slice(0, 19))}</span>`;

  if (!report.instances.length) {
    box.innerHTML = '<div class="muted">등록된 인스턴스가 없습니다.</div>';
    return;
  }
  // 이미 서버가 나쁜 순으로 정렬해 내려준다 — 오래된 것/백업 없는 것이 위로 온다
  const rows = report.instances.map((f) => {
    const last = f.lastBackupAt ? esc(String(f.lastBackupAt).replace("T", " ").slice(0, 19)) : "—";
    const elapsed = f.elapsedHours == null ? "—" : `${fmtNum(f.elapsedHours, 1)}h`;
    const verify = f.verifyStatus
      ? `<span class="verify-badge verify-${esc(f.verifyStatus)}">${esc(f.verifyStatus)}</span>`
      : '<span class="muted">미검증</span>';
    return `
      <tr class="fresh-row fresh-row-${esc(f.status)}">
        <td><span class="type-badge type-${esc(f.type)}">${esc(f.type)}</span> ${esc(f.instanceName)}</td>
        <td><span class="fresh-badge fresh-${esc(f.status)}">${esc(FRESHNESS_LABEL[f.status] ?? f.status)}</span></td>
        <td>${last}</td>
        <td class="num">${elapsed}</td>
        <td>${verify}</td>
      </tr>`;
  }).join("");
  box.innerHTML = `
    <div class="table-scroll">
      <table class="qtable freshness-table">
        <thead><tr><th>인스턴스</th><th>신선도</th><th>마지막 백업</th><th>경과</th><th>복원 검증</th></tr></thead>
        <tbody>${rows}</tbody>
      </table>
    </div>`;
}

// ---------- 활동 그래프 (드래그 구간 선택) ----------
async function loadActivity() {
  const now = new Date();
  const from = toLocalInput(new Date(now - 3 * 3600 * 1000)); // 최근 3시간
  const to = toLocalInput(now);
  state.activity = await api(`/api/instances/${state.instance.id}/activity?from=${from}&to=${to}`);
  drawChart();
}

const CHART = { w: 1000, h: 180, padL: 46, padR: 10, padT: 12, padB: 22 };

function chartScales() {
  const pts = state.activity;
  const t0 = new Date(pts[0].time).getTime();
  const t1 = new Date(pts[pts.length - 1].time).getTime();
  const maxQ = Math.max(...pts.map((p) => p.qps), 1);
  const x = (t) => CHART.padL + (t - t0) / Math.max(t1 - t0, 1) * (CHART.w - CHART.padL - CHART.padR);
  const y = (q) => CHART.h - CHART.padB - q / maxQ * (CHART.h - CHART.padT - CHART.padB);
  const invX = (px) => t0 + (px - CHART.padL) / (CHART.w - CHART.padL - CHART.padR) * (t1 - t0);
  return { t0, t1, maxQ, x, y, invX };
}

function drawChart() {
  const svg = $("#activity-chart");
  const pts = state.activity;
  svg.setAttribute("viewBox", `0 0 ${CHART.w} ${CHART.h}`);
  if (pts.length < 2) {
    svg.innerHTML = "";
    $("#chart-empty").hidden = false;
    return;
  }
  $("#chart-empty").hidden = true;
  const s = chartScales();

  // 선택 구간 하이라이트 (조회=초록, 비교=주황)
  const selRect = (sel, color) => sel
    ? `<rect x="${s.x(sel.from.getTime())}" y="${CHART.padT}"
        width="${Math.max(s.x(sel.to.getTime()) - s.x(sel.from.getTime()), 2)}"
        height="${CHART.h - CHART.padT - CHART.padB}" fill="${color}" opacity="0.22"/>` : "";

  // y축 눈금 3개 + 시간 라벨 4개
  const yTicks = [0, 0.5, 1].map((r) => {
    const q = s.maxQ * r;
    return `<line x1="${CHART.padL}" y1="${s.y(q)}" x2="${CHART.w - CHART.padR}" y2="${s.y(q)}" stroke="#eef0f3"/>
            <text x="${CHART.padL - 6}" y="${s.y(q) + 4}" text-anchor="end" font-size="10" fill="#7b8494">${fmtNum(q, 0)}</text>`;
  }).join("");
  const xTicks = [0, 1 / 3, 2 / 3, 1].map((r) => {
    const t = s.t0 + (s.t1 - s.t0) * r;
    const d = new Date(t);
    const label = `${String(d.getHours()).padStart(2, "0")}:${String(d.getMinutes()).padStart(2, "0")}`;
    return `<text x="${s.x(t)}" y="${CHART.h - 6}" text-anchor="middle" font-size="10" fill="#7b8494">${label}</text>`;
  }).join("");

  const line = pts.map((p, i) =>
    `${i === 0 ? "M" : "L"}${s.x(new Date(p.time).getTime()).toFixed(1)},${s.y(p.qps).toFixed(1)}`).join(" ");

  svg.innerHTML = `
    ${yTicks}${xTicks}
    ${selRect(state.selections.base, "#f08c2d")}
    ${selRect(state.selections.target, "#22a06b")}
    <path d="${line}" fill="none" stroke="#6672f5" stroke-width="1.8"/>`;
}

// 드래그로 구간 선택 -> datetime 입력에 반영
function setupChartDrag() {
  const svg = $("#activity-chart");
  let dragStart = null;

  const pxOf = (ev) => {
    const rect = svg.getBoundingClientRect();
    return (ev.clientX - rect.left) / rect.width * CHART.w;
  };

  svg.addEventListener("pointerdown", (ev) => {
    if (!state.dragMode || state.activity.length < 2) return;
    dragStart = pxOf(ev);
    svg.setPointerCapture(ev.pointerId);
  });
  svg.addEventListener("pointermove", (ev) => {
    if (dragStart == null) return;
    const s = chartScales();
    const a = Math.min(dragStart, pxOf(ev)), b = Math.max(dragStart, pxOf(ev));
    state.selections[state.dragMode] = { from: new Date(s.invX(a)), to: new Date(s.invX(b)) };
    drawChart();
  });
  svg.addEventListener("pointerup", () => {
    if (dragStart == null) return;
    dragStart = null;
    const sel = state.selections[state.dragMode];
    if (!sel) return;
    const prefix = state.dragMode; // 'target' | 'base'
    $(`#${prefix}-from`).value = toLocalInput(sel.from);
    $(`#${prefix}-to`).value = toLocalInput(sel.to);
  });

  $("#mode-target").addEventListener("click", () => toggleDragMode("target"));
  $("#mode-base").addEventListener("click", () => toggleDragMode("base"));
}

function toggleDragMode(mode) {
  state.dragMode = state.dragMode === mode ? null : mode;
  $("#mode-target").classList.toggle("active", state.dragMode === "target");
  $("#mode-base").classList.toggle("active", state.dragMode === "base");
}

// ---------- Top Query: 단순 조회 ----------
async function runQuery() {
  state.compareMode = false;
  $("#compare-summary").hidden = true;
  closeDetail();
  const stats = await api(`/api/instances/${state.instance.id}/query-stats?limit=20`);
  const table = $("#top-table");
  table.querySelector("thead").innerHTML = `
    <tr><th>Load</th><th>Query</th><th class="num">Calls</th>
        <th class="num">Total(ms)</th><th class="num">Rows Examined</th></tr>`;
  table.querySelector("tbody").innerHTML = stats.map((q, idx) => `
    <tr data-idx="${idx}">
      <td class="num">${fmtNum(q.loadPct)}%</td>
      <td class="qtext" title="${esc(q.queryText)}">${esc(q.queryText)}</td>
      <td class="num">${fmtNum(q.calls, 0)}</td>
      <td class="num">${fmtNum(q.totalTimeMs)}</td>
      <td class="num">${fmtNum(q.rowsExamined, 0)}</td>
    </tr>`).join("");
  bindRowClicks(stats.map((q) => ({ queryId: q.queryId, queryText: q.queryText })));
}

// ---------- Top Query: 비교 조회 (증감 + NEW) ----------
async function runCompare() {
  const p = (id) => $(id).value;
  if (!p("#base-from") || !p("#base-to") || !p("#target-from") || !p("#target-to")) return;
  closeDetail();
  const qs = `baseFrom=${p("#base-from")}&baseTo=${p("#base-to")}&targetFrom=${p("#target-from")}&targetTo=${p("#target-to")}`;
  let result;
  try {
    result = await api(`/api/instances/${state.instance.id}/compare?${qs}`);
  } catch (e) {
    alert(`비교 실패: ${e.message}\n(구간 안에 스냅샷 배치가 2개 이상 필요합니다 — 수집 주기 1분)`);
    return;
  }
  state.compareMode = true;

  // 요약 스트립 — 표를 읽기 전에 "전반적으로 무엇이 변했는지"
  const sum = $("#compare-summary");
  sum.hidden = false;
  const pct = (v) => v == null ? "-" : `<span class="${v >= 0 ? "delta-up" : "delta-down"}">${v >= 0 ? "+" : ""}${fmtNum(v, 0)}%</span>`;
  sum.innerHTML = `
    <span class="summary-item">호출량 ${pct(result.totalCallsChangePct)}</span>
    <span class="summary-item">평균 레이턴시 ${pct(result.avgLatencyChangePct)}</span>
    <span class="summary-item">읽은 행수 ${pct(result.rowsExaminedChangePct)}</span>
    <span class="summary-item">신규 쿼리 <b>${result.newQueryCount}</b>개</span>
    <span class="summary-item muted">조회 ${esc($("#target-from").value.replace("T", " "))} ~ ${esc($("#target-to").value.slice(11))}
      / 비교 ${esc($("#base-from").value.replace("T", " "))} ~ ${esc($("#base-to").value.slice(11))}</span>`;

  // 표: target QPS 내림차순, 신규 쿼리 하이라이트
  const rows = [...result.queries].sort((a, b) => b.targetQps - a.targetQps);
  const table = $("#top-table");
  table.querySelector("thead").innerHTML = `
    <tr><th>Query</th><th class="num">QPS</th><th class="num">Latency(ms)</th><th class="num">Rows/call</th></tr>`;
  table.querySelector("tbody").innerHTML = rows.map((q, idx) => `
    <tr data-idx="${idx}" class="${q.newQuery ? "new-query" : ""}">
      <td class="qtext" title="${esc(q.queryText)}">${q.newQuery ? '<span class="badge-new">NEW</span>' : ""}${esc(q.queryText)}</td>
      <td>${deltaCell(q.baseQps, q.targetQps, q.qpsChangePct)}</td>
      <td>${deltaCell(q.baseAvgMs, q.targetAvgMs, q.latencyChangePct)}</td>
      <td>${deltaCell(q.baseRowsPerCall, q.targetRowsPerCall, q.rowsPerCallChangePct, 0)}</td>
    </tr>`).join("");
  bindRowClicks(rows);
}

// ---------- 쿼리 상세 (EXPLAIN + AI) ----------
function bindRowClicks(rows) {
  $("#top-table").querySelectorAll("tbody tr").forEach((tr) => {
    tr.addEventListener("click", () => {
      document.querySelectorAll("#top-table tbody tr").forEach((r) => r.classList.remove("selected"));
      tr.classList.add("selected");
      openDetail(rows[tr.dataset.idx]);
    });
  });
}

function openDetail(query) {
  state.currentQuery = query;
  $("#query-detail").hidden = false;
  $("#detail-qid").textContent = `SQL ID: ${query.queryId}`;
  $("#detail-sql").value = query.queryText ?? "";
  $("#plan-section").hidden = true;
  $("#ai-section").hidden = true;
  $("#advisor-section").hidden = true;
  $("#advisor-columns").value = "";
  $("#advisor-result").innerHTML = "";
  $("#deep-section").hidden = true;
  $("#deep-result").innerHTML = "";
  $("#inquiry-section").hidden = true;
  $("#inquiry-note").value = "";
  $("#inquiry-result").innerHTML = "";
  // 쿼리를 새로 열면 직전 쿼리의 분석 결과는 첨부 대상이 아니다 — 비운다
  state.lastPlan = null;
  state.lastFindings = [];
  state.lastAi = null;
  $("#query-detail").scrollIntoView({ behavior: "smooth", block: "nearest" });
}

function closeDetail() {
  $("#query-detail").hidden = true;
  state.currentQuery = null;
}

async function runExplain() {
  const sql = $("#detail-sql").value.trim();
  if (!sql) return;
  const btn = $("#btn-explain");
  btn.classList.add("loading");
  try {
    let data;
    $("#plan-section").hidden = false;
    try {
      data = await api(`/api/instances/${state.instance.id}/explain`, {
        method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ sql }),
      });
    } catch (e) {
      $("#detail-plan").textContent = `실행 실패: ${e.message}`;
      $("#detail-findings").innerHTML = "";
      return;
    }
    $("#detail-plan").textContent = data.plan;
    $("#detail-findings").innerHTML = (data.findings ?? []).map((f) =>
      `<div class="finding-item">${esc(f)}</div>`).join("") ||
      '<div class="muted">규칙 기반 지적 없음 — 비효율 신호가 발견되지 않았습니다.</div>';
    state.lastPlan = data.plan;
    state.lastFindings = data.findings ?? [];
  } finally { btn.classList.remove("loading"); }
}

async function runAiAnalysis() {
  const sql = $("#detail-sql").value.trim();
  if (!sql) return;
  const btn = $("#btn-ai");
  btn.classList.add("loading");
  $("#ai-section").hidden = false;
  $("#detail-ai").textContent = "분석 중... (실행계획 조회 후 AI 판정)";
  try {
    let data;
    try {
      data = await api(`/api/instances/${state.instance.id}/ai-analysis`, {
        method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ sql }),
      });
    } catch (e) { $("#detail-ai").textContent = `실패: ${e.message}`; return; }
    // 실행계획 섹션도 함께 갱신 (같은 응답에 plan/findings 포함)
    $("#plan-section").hidden = false;
    $("#detail-plan").textContent = data.plan;
    $("#detail-findings").innerHTML = (data.findings ?? []).map((f) =>
      `<div class="finding-item">${esc(f)}</div>`).join("");
    $("#detail-ai").textContent = data.aiAnalysis ??
      "AI 분석 비활성화 상태입니다 (ANTHROPIC_API_KEY도 claude CLI도 없음) — 규칙 기반 지적까지만 표시합니다.";
    state.lastPlan = data.plan;
    state.lastFindings = data.findings ?? [];
    state.lastAi = data.aiAnalysis ?? null;
  } finally { btn.classList.remove("loading"); }
}

// 인덱스 어드바이저 — 후보 컬럼으로 가상 인덱스를 만들었을 때 플랜 비용이 어떻게 바뀌는지 시뮬레이션.
// PostgreSQL은 HypoPG로 실제 인덱스 없이 before/after 비용을 비교하고, 타 기종은 UNSUPPORTED를 그대로 보여준다.
const ADVISOR_STATUS = {
  ADVISED: { cls: "advised", label: "제안" },
  NO_BENEFIT: { cls: "no-benefit", label: "이득 없음" },
  UNSUPPORTED: { cls: "unsupported", label: "미지원" },
};

async function runIndexAdvisor() {
  const sql = $("#detail-sql").value.trim();
  if (!sql) return;
  const columns = $("#advisor-columns").value.trim();
  const btn = $("#btn-advisor-run");
  btn.classList.add("loading");
  const result = $("#advisor-result");
  result.innerHTML = '<div class="muted">가상 인덱스로 시뮬레이션 중...</div>';
  try {
    let data;
    try {
      data = await api(`/api/instances/${state.instance.id}/index-advisor`, {
        method: "POST", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ sql, columns: columns || null }),
      });
    } catch (e) {
      result.innerHTML = `<div class="finding-item">시뮬레이션 실패: ${esc(e.message)}</div>`;
      return;
    }
    const meta = ADVISOR_STATUS[data.status] || { cls: "unsupported", label: data.status };
    let html = `<div class="finding-item"><span class="advisor-status ${meta.cls}">${esc(meta.label)}</span>${esc(data.detail)}</div>`;
    if (data.suggestedIndex) {
      html += `<div class="finding-item">제안 인덱스: <code>${esc(data.suggestedIndex)}</code></div>`;
    }
    if (data.beforeCost != null && data.afterCost != null) {
      html += `<div class="finding-item">Total Cost: ${esc(data.beforeCost)} → ${esc(data.afterCost)}</div>`;
    }
    if (data.beforePlan) {
      html += `<h3>변경 전 실행계획</h3><pre class="codeblock">${esc(data.beforePlan)}</pre>`;
    }
    if (data.afterPlan) {
      html += `<h3>가상 인덱스 적용 후 실행계획</h3><pre class="codeblock">${esc(data.afterPlan)}</pre>`;
    }
    result.innerHTML = html;
  } finally { btn.classList.remove("loading"); }
}

// 심층 원인 진단 (D9) — 실제 실행 계획으로 카디널리티 괴리·근본원인을 짚는다.
// explain(추정)과 달리 쿼리를 실제 실행하므로 ADMIN 전용(서버가 인가). 파라미터 자리는 실제 값이어야 한다.
async function runDeepDiagnose() {
  const sql = $("#detail-sql").value.trim();
  if (!sql) return;
  const btn = $("#btn-deep");
  btn.classList.add("loading");
  $("#deep-section").hidden = false;
  const result = $("#deep-result");
  result.innerHTML = '<div class="muted">실제 실행 계획으로 진단 중... (쿼리를 실제 실행 — 타임아웃 적용)</div>';
  try {
    let data;
    try {
      data = await api(`/api/instances/${state.instance.id}/deep-diagnose`, {
        method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ sql }),
      });
    } catch (e) {
      result.innerHTML = `<div class="finding-item">진단 실패: ${esc(e.message)}</div>`;
      return;
    }
    let html = "";
    if (data.worstGap) {
      const g = data.worstGap;
      html += `<div class="finding-item"><strong>카디널리티 오추정</strong> — ${esc(g.node)}: `
        + `추정 ${fmtNum(g.estimatedRows, 0)}행 vs 실제 ${fmtNum(g.actualRows, 0)}행 `
        + `(약 ${fmtNum(g.ratio)}배 괴리, loops=${fmtNum(g.loops, 0)})</div>`;
    } else {
      html += `<div class="muted">추정·실제 행수 괴리(10배+) 지점 없음 — 카디널리티는 대체로 맞음.</div>`;
    }
    if ((data.rootCauses ?? []).length) {
      html += (data.rootCauses).map((c) =>
        `<div class="finding-item"><span class="advisor-status unsupported">${esc(c.cause)}</span>`
        + `<div class="advisor-finding-detail">신호: ${esc(c.signal)}</div>`
        + `<div class="advisor-finding-reco">${esc(c.detail)}</div></div>`).join("");
    } else {
      html += `<div class="muted">근본원인 규칙 매칭 없음 — 형변환·컬럼함수·선두 누락 신호가 발견되지 않음.</div>`;
    }
    if ((data.notes ?? []).length) {
      html += `<div class="advisor-note muted">${data.notes.map(esc).join(" · ")}</div>`;
    }
    html += `<h3>실제 실행 계획</h3><pre class="codeblock">${esc(data.plan)}</pre>`;
    result.innerHTML = html;
  } finally { btn.classList.remove("loading"); }
}

// 현재 상세 패널의 쿼리·실행계획·규칙 지적·AI 분석을 모아 DB팀에 문의(웹훅 push).
// 실행계획/AI를 안 돌렸어도 쿼리만으로 문의할 수 있게 plan/findings/ai는 있으면 첨부한다.
async function runInquiry() {
  const sql = $("#detail-sql").value.trim();
  if (!sql) return;
  const btn = $("#btn-inquiry");
  btn.classList.add("loading");
  $("#inquiry-section").hidden = false;
  const result = $("#inquiry-result");
  result.innerHTML = '<div class="muted">전송 중...</div>';
  try {
    const body = {
      sql,
      plan: state.lastPlan,
      findings: state.lastFindings,
      aiAnalysis: state.lastAi,
      note: $("#inquiry-note").value.trim() || null,
    };
    let data;
    try {
      data = await api(`/api/instances/${state.instance.id}/inquiry`, {
        method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body),
      });
    } catch (e) {
      result.innerHTML = `<div class="finding-item">문의 실패: ${esc(e.message)}</div>`;
      return;
    }
    result.innerHTML = data.sent
      ? '<div class="finding-item">DB팀에 전송되었습니다.</div>'
      : `<div class="finding-item">전송되지 않음 — ${esc(data.reason ?? "웹훅 미설정")}</div>`;
  } finally { btn.classList.remove("loading"); }
}

// ---------- Slow Query / Monitoring ----------
async function loadSlow() {
  const rows = await api(`/api/instances/${state.instance.id}/slow-queries?limit=20`);
  const table = $("#slow-table");
  table.querySelector("thead").innerHTML = `
    <tr><th>Captured</th><th class="num">Elapsed(ms)</th><th class="num">Rows</th><th>Query</th></tr>`;
  table.querySelector("tbody").innerHTML = rows.length ? rows.map((q) => `
    <tr>
      <td class="num">${esc(q.capturedAt ?? "-")}</td>
      <td class="num">${fmtNum(q.elapsedMs)}</td>
      <td class="num">${fmtNum(q.rowsExamined, 0)}</td>
      <td class="qtext" title="${esc(q.queryText)}">${esc(q.queryText)}</td>
    </tr>`).join("") : '<tr><td colspan="4" class="muted">슬로우 쿼리가 없습니다.</td></tr>';
}

// MCP 카드 — 도구 목록을 실제 /mcp 엔드포인트(tools/list)에서 받아와 그린다.
// 하드코딩하지 않는 이유: 이 목록이 곧 "MCP가 살아 있다"의 증거가 되기 때문.
async function loadMcpTools() {
  const box = $("#mcp-tools");
  try {
    const data = await api("/mcp", {
      method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ jsonrpc: "2.0", id: 1, method: "tools/list" }),
    });
    box.classList.remove("muted");
    box.innerHTML = data.result.tools.map((t) => `
      <div class="mcp-tool"><b>${esc(t.name)}</b><p>${esc(t.description)}</p></div>`).join("");
  } catch (e) {
    box.textContent = e.message.startsWith("403")
      ? "MCP 카드는 ADMIN 역할만 볼 수 있습니다 (서비스 토큰 노출 방지)"
      : `도구 목록 조회 실패: ${e.message}`;
  }
}

// MCP 등록 명령 — ADMIN이면 서비스 토큰을 받아 실제 명령을 완성한다 (A1)
async function loadMcpCommand() {
  try {
    const { token } = await api("/api/security/mcp-token");
    $("#mcp-cmd-http").textContent =
      `claude mcp add --transport http dbtower http://localhost:8080/mcp --header "Authorization: Bearer ${token}"`;
  } catch { /* VIEWER — 기본 안내 문구 유지 */ }
}

// 상단 사용자 표시 + 로그아웃
async function loadMe() {
  try {
    const me = await api("/api/me");
    state.role = me.role;
    $("#user-chip").innerHTML =
      `${esc(me.username)}<span class="role-badge">${esc(me.role)}</span>`;
  } catch { /* 401이면 api()가 로그인으로 보낸다 */ }
  $("#logout-btn")?.addEventListener("click", async () => {
    await fetch("/logout", { method: "POST", headers: { "X-XSRF-TOKEN": csrfToken() } });
    location.href = "/login.html";
  });
}

function setupCopyButtons() {
  document.querySelectorAll("[data-copy]").forEach((btn) => {
    btn.addEventListener("click", async () => {
      const text = $(`#${btn.dataset.copy}`).textContent.split("   (")[0].trim();
      try {
        await navigator.clipboard.writeText(text);
        const old = btn.textContent;
        btn.textContent = "복사됨";
        setTimeout(() => { btn.textContent = old; }, 1200);
      } catch { /* http 컨텍스트 등 클립보드 불가 환경 — 무시 */ }
    });
  });
}

async function loadReplication() {
  try {
    const r = await api(`/api/instances/${state.instance.id}/replication`);
    $("#replication-box").textContent =
      `role: ${r.role}\nlagSeconds: ${r.lagSeconds}\n${r.detail ?? ""}`;
  } catch (e) { $("#replication-box").textContent = `조회 실패: ${e.message}`; }
}

// 이상 감지 (D1) — 평소(이 요일·시간대 베이스라인) 대비 z-score 이탈 쿼리 목록.
// 이력이 부족하면 판정을 보류하고 "학습 중"으로만 알린다(신규 오탐 방지) — 그 사실을 화면에 정직하게 표기한다.
async function loadAnomalies() {
  const box = $("#anomaly-result");
  try {
    const scan = await api(`/api/instances/${state.instance.id}/anomalies`);
    const parts = [];
    if (scan.anomalies.length === 0) {
      parts.push('<p class="muted">현재 이상 없음 (평소 범위 내).</p>');
    } else {
      parts.push(scan.anomalies.map((q) => {
        const metrics = q.anomalies.map((m) =>
          `<span class="anomaly-metric">${esc(m.metric)}: <b>${fmtNum(m.current)}</b> ` +
          `(평소 ${fmtNum(m.baselineMean)}±${fmtNum(m.baselineStddev)}, z=${fmtNum(m.zScore, 1)})</span>`
        ).join(" ");
        return `<div class="anomaly-item">
          <div class="anomaly-q qtext" title="${esc(q.queryText)}">${esc(q.queryText)}</div>
          <div class="anomaly-metrics">${metrics}</div>
          <div class="hint">${q.dayOfWeek}요일 ${q.hour}시대 기준 · 관측 ${q.observations}회</div>
        </div>`;
      }).join(""));
    }
    // 학습 중(이력 부족) 쿼리 수를 정직하게 노출 — "아직 판정 못 한다"를 숨기지 않는다.
    if (scan.learningCount > 0) {
      parts.push(`<p class="hint">학습 중(baseline unavailable) 쿼리 ${scan.learningCount}건 — 관측 ${scan.minObservations}회 미만이라 판정 보류.</p>`);
    }
    box.className = "anomaly-result";
    box.innerHTML = parts.join("");
  } catch (e) {
    box.className = "anomaly-result muted";
    box.textContent = `조회 실패: ${e.message}`;
  }
}

// Wait Events — 기종별 의미가 다르다(누적/순간 스냅샷/큐 게이지). 시간 정보가 없는 소스는
// totalMs=0으로 오므로 "-"로 표시해 "0ms 기다렸다"로 오독되지 않게 한다.
async function loadWaitEvents() {
  const table = $("#wait-table");
  table.querySelector("thead").innerHTML = `
    <tr><th>Category</th><th>Event</th><th class="num">Count</th><th class="num">Total(ms)</th></tr>`;
  try {
    const rows = await api(`/api/instances/${state.instance.id}/wait-events?limit=20`);
    table.querySelector("tbody").innerHTML = rows.length ? rows.map((w) => `
      <tr>
        <td>${esc(w.category)}</td>
        <td class="qtext" title="${esc(w.event)}">${esc(w.event)}</td>
        <td class="num">${fmtNum(w.count, 0)}</td>
        <td class="num">${w.totalMs > 0 ? fmtNum(w.totalMs) : "-"}</td>
      </tr>`).join("") : '<tr><td colspan="4" class="muted">대기 이벤트가 없습니다.</td></tr>';
  } catch (e) {
    table.querySelector("tbody").innerHTML =
      `<tr><td colspan="4" class="muted">조회 실패: ${esc(e.message)}</td></tr>`;
  }
}

// 레이턴시 백분위 p95/p99 (D4a) — 같은 지표라도 기종마다 원자료가 달라 source로 출처를 구분한다.
// 값을 절대 섞지 않는다: 실측(NATIVE)·직접계산(COMPUTED)·추정(ESTIMATED)·미지원(UNSUPPORTED)을
// 배지로 정직하게 구분해 보여준다. 값이 없으면(null) "-"로, 추정/미지원은 배지 색으로 오독을 막는다.
const LATENCY_SOURCE = {
  NATIVE: { cls: "src-native", label: "실측", note: "리셋 이후 누적 — 최근 윈도우 아님" },
  COMPUTED: { cls: "src-computed", label: "직접계산", note: "profile 원샘플에서 계산" },
  ESTIMATED: { cls: "src-estimated", label: "추정", note: "평균+표준편차 근사 — 실제 백분위 아님, 과소평가 가능" },
  UNSUPPORTED: { cls: "src-unsupported", label: "미지원", note: "백분위 원자료 없음" },
};

async function loadLatencyPercentiles() {
  const table = $("#latency-table");
  table.querySelector("thead").innerHTML = `
    <tr><th>Source</th><th>Query</th><th class="num">p95(ms)</th><th class="num">p99(ms)</th></tr>`;
  try {
    const rows = await api(`/api/instances/${state.instance.id}/latency-percentiles?limit=20`);
    table.querySelector("tbody").innerHTML = rows.length ? rows.map((r) => {
      const src = LATENCY_SOURCE[r.source] ?? { cls: "src-unsupported", label: esc(r.source), note: "" };
      return `
      <tr>
        <td><span class="src-badge ${src.cls}" title="${esc(src.note)}">${src.label}</span></td>
        <td class="qtext" title="${esc(r.queryText)}">${esc(r.queryText)}</td>
        <td class="num">${r.p95Ms != null ? fmtNum(r.p95Ms) : "-"}</td>
        <td class="num">${r.p99Ms != null ? fmtNum(r.p99Ms) : "-"}</td>
      </tr>`;
    }).join("") : '<tr><td colspan="4" class="muted">백분위 데이터가 없습니다.</td></tr>';
  } catch (e) {
    table.querySelector("tbody").innerHTML =
      `<tr><td colspan="4" class="muted">조회 실패: ${esc(e.message)}</td></tr>`;
  }
}

// ---------- SLO / 에러 버짓 (D4) — 사용자 경험 지표(레이턴시·가용성)로 SLO 대비 버짓 소진을 본다 ----------
// 레이턴시 SLI가 어느 source(p95 실측/직접계산/추정/평균 폴백)인지 배지로 정직하게 표기한다.
const SLO_VERDICT_LABEL = {
  MEETING: "충족", AT_RISK: "임박", BREACHING: "위반", INSUFFICIENT_DATA: "데이터 부족",
  OK: "여유", WARNING: "임박", EXHAUSTED: "소진",
};
// 레이턴시 SLI source 배지 — D4a 4종에 D4의 평균 폴백/데이터 부족을 더한다(라벨을 절대 섞지 않는다)
const SLO_LATENCY_SOURCE = {
  ...LATENCY_SOURCE,
  AVG_FALLBACK: { cls: "src-estimated", label: "평균폴백", note: "백분위 미지원 기종 — 평균 레이턴시로 폴백(꼬리 못 봄)" },
  INSUFFICIENT_DATA: { cls: "src-unsupported", label: "데이터부족", note: "쿼리 통계 없음" },
};

async function loadSloReport() {
  const box = $("#slo-result");
  box.classList.remove("muted");
  let r;
  try {
    r = await api(`/api/instances/${state.instance.id}/slo`);
  } catch (e) {
    box.classList.add("muted");
    box.textContent = `조회 실패: ${e.message}`;
    return;
  }
  const lat = r.latency, av = r.availability, eb = r.errorBudget;
  const src = SLO_LATENCY_SOURCE[lat.source] ?? { cls: "src-unsupported", label: esc(lat.source), note: "" };
  const vlabel = (v) => esc(SLO_VERDICT_LABEL[v] ?? v);

  // 버짓 소진 게이지 — 소진율(%)을 폭으로. 80%↑ 주황, 100%↑ 빨강
  const consumed = eb.budgetConsumedRatio;
  const pctConsumed = consumed == null ? null : Math.min(100, consumed * 100);
  const gaugeCls = consumed == null ? "slo-gauge-ok"
    : consumed >= 1 ? "slo-gauge-over" : consumed >= 0.8 ? "slo-gauge-warn" : "slo-gauge-ok";
  const gauge = consumed == null ? '<div class="slo-sub">데이터 부족 — 게이지 없음</div>'
    : `<div class="slo-gauge"><div class="slo-gauge-fill ${gaugeCls}" style="width:${pctConsumed.toFixed(1)}%"></div></div>
       <div class="slo-sub">소진 ${(consumed * 100).toFixed(1)}% · 잔여 ${(eb.budgetRemainingRatio * 100).toFixed(1)}%</div>`;

  const latValue = lat.observedMs == null ? "—" : `${fmtNum(lat.observedMs)} ms`;
  const latP99 = lat.p99Ms != null ? ` · p99 ${fmtNum(lat.p99Ms)}ms` : "";
  const upValue = av.upRatio == null ? "—" : `${(av.upRatio * 100).toFixed(2)}%`;
  const burn = eb.burnRate == null ? "—" : `${fmtNum(eb.burnRate)}×`;

  box.innerHTML = `
    <div style="margin-bottom:6px">
      <span class="slo-verdict slo-verdict-${esc(r.verdict)}">${vlabel(r.verdict)}</span>
      <span class="slo-sub" style="margin-left:8px">평가 ${esc(String(r.evaluatedAt).replace("T", " ").slice(0, 19))}</span>
    </div>
    <div class="slo-grid">
      <div class="slo-block">
        <h4>레이턴시 SLI <span class="src-badge ${src.cls}" title="${esc(src.note)}">${src.label}</span></h4>
        <div class="slo-metric">${latValue}</div>
        <div class="slo-sub">${lat.source === "AVG_FALLBACK" ? "평균 레이턴시" : "최악 핵심쿼리 p95"}${latP99} · 목표 &lt; ${fmtNum(lat.thresholdMs)}ms
          <span class="slo-badge slo-badge-${esc(lat.verdict)}">${vlabel(lat.verdict)}</span></div>
        ${lat.totalCoreQueries > 0 ? `<div class="slo-sub">임계 초과 핵심쿼리 ${lat.breachingCoreQueries}/${lat.totalCoreQueries}${lat.coreQueryText ? ` · 최악: ${esc(lat.coreQueryText)}` : ""}</div>` : ""}
        <div class="slo-note">${esc(lat.note)}</div>
      </div>
      <div class="slo-block">
        <h4>가용성 SLI</h4>
        <div class="slo-metric">${upValue}</div>
        <div class="slo-sub">목표 ${(av.targetRatio * 100).toFixed(2)}% · 표본 ${av.upSamples}/${av.totalSamples} (${av.windowDays}일)
          <span class="slo-badge slo-badge-${esc(av.verdict)}">${vlabel(av.verdict)}</span></div>
        <div class="slo-note">${esc(av.note)}</div>
      </div>
      <div class="slo-block" style="grid-column:1/-1">
        <h4>에러 버짓 · 번인 레이트
          <span class="slo-badge slo-badge-${esc(eb.verdict)}">${vlabel(eb.verdict)}</span></h4>
        ${gauge}
        <div class="slo-sub" style="margin-top:6px">번인 레이트 ${burn} <span class="hint">(지속가능 속도 대비 배수 · 최근 ${eb.burnWindowMinutes}분)</span>
          · 허용 ${fmtNum(eb.allowedDowntimeMinutes)}분 / 관측 ${fmtNum(eb.observedDowntimeMinutes)}분</div>
        <div class="slo-note">${esc(eb.note)}</div>
      </div>
    </div>`;
}

// 파티션 조회 (D5) — 테이블별 파티션 목록·방식·경계·행수·크기. 조회 전용(생성·삭제 없음).
// MongoDB는 partitionMethod=UNSUPPORTED 안내 행으로 오고, 이때 boundary에 사유가 담긴다 —
// "파티션 없음"과 "이 기종은 원래 파티션 개념이 없음"을 정직하게 구분해 보여준다.
async function loadPartitions() {
  const table = $("#partition-table");
  table.querySelector("thead").innerHTML = `
    <tr><th>Table</th><th>Partition</th><th>Method</th><th>Boundary</th>
        <th class="num">Rows</th><th class="num">Size</th></tr>`;
  try {
    const rows = await api(`/api/instances/${state.instance.id}/partitions?limit=50`);
    if (rows.length && rows[0].partitionMethod === "UNSUPPORTED") {
      table.querySelector("tbody").innerHTML =
        `<tr><td colspan="6" class="muted">미지원 — ${esc(rows[0].boundary)}</td></tr>`;
      return;
    }
    table.querySelector("tbody").innerHTML = rows.length ? rows.map((p) => `
      <tr>
        <td>${esc(p.tableName)}</td>
        <td>${esc(p.partitionName ?? "-")}</td>
        <td>${esc(p.partitionMethod ?? "-")}${p.partitionExpression ? ` <span class="muted">(${esc(p.partitionExpression)})</span>` : ""}</td>
        <td class="qtext" title="${esc(p.boundary ?? "")}">${esc(p.boundary ?? "-")}</td>
        <td class="num">${p.rowCount != null ? fmtNum(p.rowCount, 0) : "-"}</td>
        <td class="num">${p.sizeBytes != null ? fmtBytes(p.sizeBytes) : "-"}</td>
      </tr>`).join("") : '<tr><td colspan="6" class="muted">파티션이 있는 테이블이 없습니다.</td></tr>';
  } catch (e) {
    table.querySelector("tbody").innerHTML =
      `<tr><td colspan="6" class="muted">조회 실패: ${esc(e.message)}</td></tr>`;
  }
}

// 세션 / 블로킹 (B2) — "지금 누가 누구를 막고 있나". blockedByPid가 있으면 행을 강조한다.
// ADMIN이면 행마다 취소(force=false)/강제종료(force=true) 버튼을 붙인다. VIEWER면 버튼 없음.
async function loadSessions() {
  const table = $("#session-table");
  const isAdmin = state.role === "ADMIN";
  const cols = isAdmin ? 8 : 7;
  table.querySelector("thead").innerHTML = `
    <tr><th class="num">PID</th><th>User</th><th>State</th><th>Wait</th>
        <th class="num">BlockedBy</th><th class="num">Elapsed(ms)</th><th>Query</th>${isAdmin ? "<th>Action</th>" : ""}</tr>`;
  try {
    const rows = await api(`/api/instances/${state.instance.id}/sessions?limit=50`);
    table.querySelector("tbody").innerHTML = rows.length ? rows.map((s) => `
      <tr class="${s.blockedByPid != null ? "blocked-row" : ""}">
        <td class="num">${esc(s.pid)}</td>
        <td>${esc(s.user ?? "-")}</td>
        <td>${esc(s.state ?? "-")}</td>
        <td>${esc(s.waitEvent ?? "-")}</td>
        <td class="num">${s.blockedByPid != null ? `<span class="blocked-by">${esc(s.blockedByPid)}</span>` : "-"}</td>
        <td class="num">${fmtNum(s.elapsedMs)}</td>
        <td class="qtext" title="${esc(s.query)}">${esc(s.query ?? "-")}</td>
        ${isAdmin ? `<td class="session-actions">
          <button class="btn btn-small" data-kill="${esc(s.pid)}" data-force="false">취소</button>
          <button class="btn btn-small btn-danger" data-kill="${esc(s.pid)}" data-force="true">강제종료</button>
        </td>` : ""}
      </tr>`).join("") : `<tr><td colspan="${cols}" class="muted">활성 세션이 없습니다.</td></tr>`;
    if (isAdmin) wireKillButtons();
  } catch (e) {
    table.querySelector("tbody").innerHTML =
      `<tr><td colspan="${cols}" class="muted">조회 실패: ${esc(e.message)}</td></tr>`;
  }
}

// kill은 confirm 없이 바로 POST한다(장애 시 빠른 처치가 목적) — 대신 버튼 자체가 ADMIN에게만 보인다.
// 성공하면 목록을 다시 불러 사라졌는지 확인시킨다.
function wireKillButtons() {
  document.querySelectorAll("#session-table [data-kill]").forEach((btn) => {
    btn.addEventListener("click", async () => {
      const pid = btn.dataset.kill;
      const force = btn.dataset.force === "true";
      btn.disabled = true;
      try {
        await api(`/api/instances/${state.instance.id}/sessions/${pid}/kill?force=${force}`, { method: "POST" });
        await loadSessions();
      } catch (e) {
        btn.disabled = false;
        alert(`세션 종료 실패: ${e.message}`);
      }
    });
  });
}

// ---------- Schema Diff (B7) — 같은 역할의 두 인스턴스 구조 비교 ----------
// 드롭다운 두 개는 등록된 인스턴스 전체 목록에서 채운다(현재 선택된 인스턴스와 무관 — 두 대를 자유 비교).
function populateSchemaSelects(list) {
  const opts = list.map((i) => `<option value="${i.id}">${esc(i.name)} · ${esc(i.type)}</option>`).join("");
  // Schema Diff(B7)와 파라미터 드리프트(B6) 두 카드의 좌/우 드롭다운을 같은 목록으로 채운다
  [["#schema-left", "#schema-right"], ["#param-left", "#param-right"]].forEach(([lSel, rSel]) => {
    const left = $(lSel), right = $(rSel);
    if (!left || !right) return;
    left.innerHTML = opts;
    right.innerHTML = opts;
    if (list.length > 1) right.selectedIndex = 1; // 기본값: 서로 다른 두 대
  });
}

// 인덱스 한 줄 표기 — (col1, col2) UNIQUE. 값은 전부 esc()로 이스케이프한다(XSS 방지).
function idxText(x) {
  return `(${(x.columns || []).map(esc).join(", ")})${x.unique ? " UNIQUE" : ""}`;
}
const notNull = (nullable) => (nullable ? "" : " NOT NULL");

async function runSchemaDiff() {
  const left = $("#schema-left").value, right = $("#schema-right").value;
  const box = $("#schema-diff-result"), warnBox = $("#schema-diff-warning");
  if (!left || !right) return;
  box.classList.remove("muted");
  box.innerHTML = '<div class="muted">비교 중...</div>';
  warnBox.hidden = true;
  let d;
  try {
    d = await api(`/api/schema-diff?left=${left}&right=${right}`);
  } catch (e) {
    box.innerHTML = `<div class="schema-warning">비교 실패: ${esc(e.message)}</div>`;
    return;
  }
  if (d.warning) { warnBox.hidden = false; warnBox.textContent = `주의: ${d.warning}`; }
  if (d.identical) {
    box.innerHTML = '<div class="schema-same">두 스키마가 동일합니다 — 구조 차이 없음.</div>';
    return;
  }
  const parts = [];
  const line = (cls, mark, text) => `<div class="schema-line ${cls}">${mark} ${text}</div>`;
  const tableMeta = (t) => `<span class="muted">(${t.columns.length} cols · ${t.indexes.length} idx)</span>`;

  if (d.addedTables.length) {
    parts.push('<div class="schema-block"><h4>추가된 테이블 <span class="hint">(right에만)</span></h4>' +
      d.addedTables.map((t) => line("schema-add", "+", `${esc(t.name)} ${tableMeta(t)}`)).join("") + "</div>");
  }
  if (d.removedTables.length) {
    parts.push('<div class="schema-block"><h4>삭제된 테이블 <span class="hint">(left에만)</span></h4>' +
      d.removedTables.map((t) => line("schema-del", "−", `${esc(t.name)} ${tableMeta(t)}`)).join("") + "</div>");
  }
  d.changedTables.forEach((t) => {
    const lines = [];
    t.addedColumns.forEach((c) => lines.push(line("schema-add", "+", `컬럼 ${esc(c.name)} ${esc(c.type)}${notNull(c.nullable)}`)));
    t.removedColumns.forEach((c) => lines.push(line("schema-del", "−", `컬럼 ${esc(c.name)} ${esc(c.type)}`)));
    t.changedColumns.forEach((c) => lines.push(line("schema-chg", "~",
      `컬럼 ${esc(c.name)}: ${esc(c.leftType)}${notNull(c.leftNullable)} → ${esc(c.rightType)}${notNull(c.rightNullable)}`)));
    t.addedIndexes.forEach((x) => lines.push(line("schema-add", "+", `인덱스 ${esc(x.name)} ${idxText(x)}`)));
    t.removedIndexes.forEach((x) => lines.push(line("schema-del", "−", `인덱스 ${esc(x.name)} ${idxText(x)}`)));
    t.changedIndexes.forEach((x) => lines.push(line("schema-chg", "~",
      `인덱스 ${esc(x.name)}: ${idxText(x.left)} → ${idxText(x.right)}`)));
    parts.push(`<div class="schema-block"><h4>변경된 테이블: ${esc(t.table)}</h4>${lines.join("")}</div>`);
  });
  box.innerHTML = parts.join("");
}

// ---------- 파라미터 드리프트 (B6) — 같은 역할 두 인스턴스 설정값 비교 ----------
async function runParamDiff() {
  const left = $("#param-left").value, right = $("#param-right").value;
  const box = $("#param-diff-result"), warnBox = $("#param-diff-warning");
  if (!left || !right) return;
  box.classList.remove("muted");
  box.innerHTML = '<div class="muted">비교 중...</div>';
  warnBox.hidden = true;
  let d;
  try {
    d = await api(`/api/param-diff?left=${left}&right=${right}`);
  } catch (e) {
    box.innerHTML = e.message.startsWith("403")
      ? '<div class="schema-warning">파라미터 드리프트는 ADMIN 역할만 볼 수 있습니다.</div>'
      : `<div class="schema-warning">비교 실패: ${esc(e.message)}</div>`;
    return;
  }
  if (d.warning) { warnBox.hidden = false; warnBox.textContent = `주의: ${d.warning}`; }
  if (d.identical) {
    box.innerHTML = '<div class="schema-same">두 인스턴스 파라미터가 동일합니다 — 드리프트 없음.</div>';
    return;
  }
  const parts = [];
  const line = (cls, mark, text) => `<div class="schema-line ${cls}">${mark} ${text}</div>`;
  if (d.changed.length) {
    // 값이 다른 항목은 표로 — name / left / right 한눈에 비교
    const rows = d.changed.map((c) => `
      <tr>
        <td class="qtext" title="${esc(c.name)}">${esc(c.name)}</td>
        <td>${esc(c.leftValue)}</td>
        <td>${esc(c.rightValue)}</td>
      </tr>`).join("");
    parts.push(`<div class="schema-block"><h4>값이 다른 파라미터 <span class="hint">(${d.changed.length})</span></h4>
      <div class="table-scroll"><table class="qtable param-diff-table">
        <thead><tr><th>name</th><th>left</th><th>right</th></tr></thead>
        <tbody>${rows}</tbody></table></div></div>`);
  }
  if (d.leftOnly.length) {
    parts.push('<div class="schema-block"><h4>left에만 있는 파라미터</h4>' +
      d.leftOnly.map((p) => line("schema-del", "−", `${esc(p.name)} = ${esc(p.value)}`)).join("") + "</div>");
  }
  if (d.rightOnly.length) {
    parts.push('<div class="schema-block"><h4>right에만 있는 파라미터</h4>' +
      d.rightOnly.map((p) => line("schema-add", "+", `${esc(p.name)} = ${esc(p.value)}`)).join("") + "</div>");
  }
  box.innerHTML = parts.join("");
}

// ---------- 온라인 스키마 변경 (B4) — gh-ost, MySQL 전용 ----------
// 기본은 dry-run(noop). "실제 실행"은 confirm으로 한 번 더 막는다(파괴적 행위).
// 결과 3-값(OK/FAILED/UNSUPPORTED)을 색으로 구분해 정직하게 보여준다.
async function runOnlineDdl(execute) {
  const box = $("#ddl-result");
  if (!state.instance) { box.className = "ddl-result schema-warning"; box.textContent = "인스턴스를 먼저 선택하세요."; return; }
  const table = $("#ddl-table").value.trim(), alter = $("#ddl-alter").value.trim();
  if (!table || !alter) { box.className = "ddl-result schema-warning"; box.textContent = "테이블과 ALTER 절을 모두 입력하세요."; return; }
  if (execute && !confirm(`실제로 ${esc(table)} 테이블에 ALTER를 적용합니다.\n\n${alter}\n\n계속할까요?`)) return;

  box.className = "ddl-result muted";
  box.textContent = execute ? "gh-ost 실행 중..." : "gh-ost dry-run(noop) 중...";
  let d;
  try {
    d = await api(`/api/instances/${state.instance.id}/online-ddl`, {
      method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ table, alter, execute }),
    });
  } catch (e) {
    box.className = "ddl-result schema-warning";
    box.textContent = `요청 실패: ${e.message}`;
    return;
  }
  const cls = { OK: "schema-same", FAILED: "schema-warning", UNSUPPORTED: "muted" }[d.status] || "muted";
  box.className = `ddl-result ${cls}`;
  const ghost = d.ghostTable ? ` · 고스트 테이블: ${esc(d.ghostTable)}` : "";
  box.innerHTML = `<strong>${esc(d.status)}</strong>${d.mode ? ` (${esc(d.mode)})` : ""}${ghost}<br>${esc(d.detail || "")}`;
}

// ---------- 자연어 근본원인 진단 (D3) ----------
async function runDiagnose() {
  const box = $("#diagnose-result");
  if (!state.instance) { box.className = "diagnose-result schema-warning"; box.textContent = "인스턴스를 먼저 선택하세요."; return; }
  const question = $("#diagnose-question").value.trim();
  if (!question) { box.className = "diagnose-result schema-warning"; box.textContent = "질문을 입력하세요."; return; }

  box.className = "diagnose-result muted";
  box.textContent = "AI가 도구를 연쇄 호출하며 진단 중... (수 초~수십 초 걸릴 수 있습니다)";
  let d;
  try {
    d = await api(`/api/instances/${state.instance.id}/diagnose`, {
      method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ question }),
    });
  } catch (e) {
    box.className = "diagnose-result schema-warning";
    box.textContent = `진단 실패: ${e.message}`;
    return;
  }

  box.className = "diagnose-result";
  if (!d.aiEnabled) {
    box.innerHTML = `<div class="diagnose-note muted">${esc(d.note || "AI 진단 비활성")}</div>`;
    return;
  }

  // 사용한 도구(투명성) — 어떤 도구를 왜 불렀나, 거부된 요청도 표시
  const calls = (d.toolCalls || []).map((c, i) => {
    const badge = c.rejected
      ? `<span class="sev-badge sev-CRITICAL">거부</span>`
      : `<span class="src-badge src-native">${i + 1}</span>`;
    return `
      <div class="diagnose-step">
        <div class="diagnose-step-head">${badge} <code>${esc(c.tool)}</code>
          <span class="muted diagnose-step-args">${esc(c.arguments || "")}</span></div>
        <div class="diagnose-step-reason">${esc(c.reason || "")}</div>
      </div>`;
  }).join("");

  const conf = esc(d.confidence || "");
  box.innerHTML = `
    <div class="diagnose-answer">
      <div class="diagnose-answer-head">
        <strong>근본원인</strong>
        <span class="src-badge conf-${conf}">확신도 ${conf}</span>
        <span class="muted">${esc(d.backend || "")} · 사용 도구 ${d.toolCallCount}개</span>
      </div>
      ${d.rootCause ? `<div class="diagnose-rootcause">${esc(d.rootCause)}</div>` : ""}
      <div class="diagnose-text">${esc(d.answer || "(답변 없음)")}</div>
    </div>
    <div class="diagnose-steps">
      <div class="diagnose-steps-head muted">AI가 부른 도구 (근거·투명성)</div>
      ${calls || '<div class="muted">호출한 도구 없음</div>'}
    </div>
    ${d.note ? `<div class="diagnose-note muted">${esc(d.note)}</div>` : ""}`;
}

// ---------- 감사 로그 검색 (Specification 동적 필터) ----------
async function loadAudit() {
  const table = $("#audit-table");
  const qs = new URLSearchParams();
  const p = $("#audit-principal").value.trim();
  const a = $("#audit-action").value.trim();
  const o = $("#audit-outcome").value.trim();
  if (p) qs.set("principal", p);
  if (a) qs.set("action", a);
  if (o) qs.set("outcome", o);
  qs.set("limit", "50");
  try {
    const rows = await api(`/api/audit?${qs.toString()}`);
    table.querySelector("tbody").innerHTML = rows.length ? rows.map((e) => `
      <tr>
        <td>${esc((e.occurredAt || "").replace("T", " ").slice(0, 19))}</td>
        <td>${esc(e.principal)}</td>
        <td>${esc(e.role ?? "-")}</td>
        <td class="qtext" title="${esc(e.action)}">${esc(e.action)}</td>
        <td class="num">${e.outcome}</td>
        <td class="num">${e.durationMs == null ? "-" : e.durationMs}</td>
      </tr>`).join("") : '<tr><td colspan="6" class="muted">조건에 맞는 기록이 없습니다.</td></tr>';
  } catch (e) {
    table.querySelector("tbody").innerHTML = e.message.startsWith("403")
      ? '<tr><td colspan="6" class="muted">감사 로그는 ADMIN 역할만 볼 수 있습니다.</td></tr>'
      : `<tr><td colspan="6" class="muted">조회 실패: ${esc(e.message)}</td></tr>`;
  }
}

// ---------- 탭/프리셋/초기화 ----------
function setupTabs() {
  document.querySelectorAll(".tab").forEach((tab) => {
    tab.addEventListener("click", () => {
      document.querySelectorAll(".tab").forEach((t) => t.classList.remove("active"));
      tab.classList.add("active");
      ["top", "slow", "monitor"].forEach((name) => {
        $(`#tab-${name}`).hidden = name !== tab.dataset.tab;
      });
    });
  });
}

function setupPresets() {
  document.querySelectorAll(".preset").forEach((btn) => {
    btn.addEventListener("click", () => {
      const mins = Number(btn.dataset.mins);
      const now = new Date();
      $("#target-to").value = toLocalInput(now);
      $("#target-from").value = toLocalInput(new Date(now - mins * 60000));
      $("#base-to").value = toLocalInput(new Date(now - mins * 60000));
      $("#base-from").value = toLocalInput(new Date(now - 2 * mins * 60000));
    });
  });
}

document.addEventListener("DOMContentLoaded", async () => {
  // 세션 kill 버튼 노출 여부가 역할에 달려 있어, 인스턴스 로딩(→세션 표) 전에 역할을 먼저 확정한다
  await loadMe();
  loadMcpCommand();
  loadInstances();
  loadHealthScore();     // 함대 전체 통합 헬스 스코어 (D8) — 나쁜 순 정렬, 대시보드 상단 상시 뷰
  loadBackupFreshness(); // 함대 전체 백업 신선도 (D7) — 인스턴스 선택과 무관한 상시 뷰
  setupTabs();
  setupPresets();
  setupChartDrag();
  setupCopyButtons();
  loadMcpTools();
  $("#btn-query").addEventListener("click", runQuery);
  $("#btn-compare").addEventListener("click", runCompare);
  $("#btn-explain").addEventListener("click", runExplain);
  $("#btn-ai").addEventListener("click", runAiAnalysis);
  // "인덱스 제안" 버튼은 섹션을 펼치고, 섹션 안의 "시뮬레이션" 버튼이 실제 호출한다(후보 컬럼 입력이 필요해서)
  $("#btn-advisor").addEventListener("click", () => { $("#advisor-section").hidden = false; $("#advisor-columns").focus(); });
  $("#btn-advisor-run").addEventListener("click", runIndexAdvisor);
  $("#btn-deep").addEventListener("click", runDeepDiagnose);
  $("#btn-inquiry").addEventListener("click", runInquiry);
  $("#btn-schema-diff").addEventListener("click", runSchemaDiff);
  $("#btn-param-diff").addEventListener("click", runParamDiff);
  $("#btn-ddl-noop").addEventListener("click", () => runOnlineDdl(false));
  $("#btn-ddl-exec").addEventListener("click", () => runOnlineDdl(true));
  $("#btn-diagnose").addEventListener("click", runDiagnose);
  $("#diagnose-question").addEventListener("keydown", (e) => { if (e.key === "Enter") runDiagnose(); });
  $("#audit-search-btn").addEventListener("click", loadAudit);
  $("#audit-reset-btn").addEventListener("click", () => {
    ["audit-principal", "audit-action", "audit-outcome"].forEach((id) => { $(`#${id}`).value = ""; });
    loadAudit();
  });
});
