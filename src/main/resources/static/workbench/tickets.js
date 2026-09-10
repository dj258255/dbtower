// 변경 티켓 — 리뷰 게이트의 요청을 워크벤치에서 드라이런·승인·실행·되돌리기까지 잇고, 실행마다 전후 비교를 보여준다.
// 상태 전이와 판정은 전부 서버(리뷰 게이트·실행 계층)가 한다. 이 화면은 상태에 맞는 버튼을 보여줄 뿐이다.

import { request, esc, ApiError } from "./api.js";
import { highlight } from "./editor.js";
import { renderDiff, renderSchemaDiff, renderProbe, renderWorkload } from "./diff.js";

const STATUS = {
  PENDING: ["대기", "st-pending"],
  APPROVED: ["승인", "st-approved"],
  REJECTED: ["반려", "st-rejected"],
  EXECUTING: ["실행 중", "st-running"],
  EXECUTED: ["실행됨", "st-executed"],
  ROLLING_BACK: ["되돌리는 중", "st-running"],
  ROLLED_BACK: ["되돌림", "st-rolled"],
};
const ACTION = { DRY_RUN: "드라이런", EXECUTE: "실행", REVERT_DRY_RUN: "되돌리기 드라이런", REVERT: "되돌리기" };
const OUTCOME = {
  RUNNING: ["진행 중(결과 미확인)", "o-warn"],
  COMMITTED: ["커밋", "o-ok"],
  ROLLED_BACK: ["롤백(흔적 없음)", "o-info"],
  FAILED: ["실패(롤백됨)", "o-bad"],
  CONFLICT: ["충돌(아무것도 쓰지 않음)", "o-warn"],
  UNCERTAIN: ["커밋 여부 불명", "o-bad"],
};
// 되돌릴 수 없거나 대상 DB에 흔적을 남기는 동작은 한 번 더 눌러야 나간다(브라우저 확인창 대신 버튼 자체로)
const ARMED = new Set(["execute", "execute-raw", "revert", "approve"]);
const time = (t) => (t ? String(t).replace("T", " ").slice(0, 19) : "");

export class TicketPanel {
  constructor({ list, detail, count, isAdmin, onOpenSql }) {
    Object.assign(this, { list, detail, count, isAdmin, onOpenSql });
    this.instanceId = null;
    this.tickets = [];
    this.executions = [];
    this.selected = null;
    this.message = null;
    this.armed = null;
    this.captureHint = false;
    list.addEventListener("click", (e) => {
      const li = e.target.closest("[data-ticket]");
      if (li) this.select(Number(li.dataset.ticket));
    });
    detail.addEventListener("click", (e) => this.onClick(e));
  }

  async load(instanceId, selectId) {
    this.instanceId = instanceId;
    this.selected = null;
    if (!(await this.reloadList())) return;
    const wanted = selectId ? Number(selectId) : null;
    if (wanted && this.tickets.some((t) => t.id === wanted)) {
      await this.select(wanted);
    } else {
      this.detail.innerHTML = this.tickets.length
        ? '<div class="muted">티켓을 고르세요.</div>'
        : '<div class="wb-empty">아직 변경 요청이 없습니다.<br>편집기에서 변경 문장을 실행하면 "변경 요청으로 올리기"가 나옵니다.</div>';
    }
  }

  async reloadList() {
    try {
      this.tickets = await request(`/api/instances/${encodeURIComponent(this.instanceId)}/reviews`);
    } catch (e) {
      this.list.innerHTML = `<div class="muted">티켓을 불러오지 못했습니다: ${esc(e.message)}</div>`;
      return false;
    }
    const open = this.tickets.filter((t) => ["PENDING", "APPROVED", "EXECUTING", "ROLLING_BACK"].includes(t.status)).length;
    this.count.textContent = open || "";
    this.list.innerHTML = this.tickets.length ? `<ul class="tk-items">${this.tickets.map((t) => {
      const [label, cls] = STATUS[t.status] || [t.status, ""];
      return `<li data-ticket="${esc(t.id)}" class="${t.id === this.selected ? "active" : ""}">
        <span class="tk-id">#${esc(t.id)}</span><span class="tk-st ${cls}">${esc(label)}</span>
        <span class="tk-sql">${esc(t.targetSql.replace(/\s+/g, " ").slice(0, 90))}</span></li>`;
    }).join("")}</ul>` : "";
    return true;
  }

  async select(id) {
    this.selected = id;
    this.message = null;
    this.armed = null;
    this.captureHint = false;
    await this.refresh();
  }

  ticket() {
    return this.tickets.find((t) => t.id === this.selected);
  }

  async refresh() {
    await this.reloadList();
    const t = this.ticket();
    if (!t) return;
    try {
      this.executions = await request(`/api/workbench/tickets/${encodeURIComponent(t.id)}/executions`);
    } catch (e) {
      this.executions = [];
      this.message = { error: true, text: `실행 기록을 불러오지 못했습니다: ${e.message}` };
    }
    this.render();
  }

  render() {
    const t = this.ticket();
    if (!t) return;
    const [label, cls] = STATUS[t.status] || [t.status, ""];
    const findings = (t.findings || []).filter(Boolean).map((f) => `<li>${esc(f)}</li>`).join("");
    const steps = [
      `제출 ${esc(t.requester)} · ${esc(time(t.submittedAt))}`,
      t.decidedBy ? `${t.status === "REJECTED" ? "반려" : "승인"} ${esc(t.decidedBy)} · ${esc(time(t.decidedAt))}${t.decisionComment ? " · " + esc(t.decisionComment) : ""}` : null,
      t.executedBy ? `실행 ${esc(t.executedBy)} · ${esc(time(t.executedAt))}` : null,
      t.rolledBackBy ? `되돌림 ${esc(t.rolledBackBy)} · ${esc(time(t.rolledBackAt))}` : null,
    ].filter(Boolean).map((s) => `<li>${s}</li>`).join("");
    const message = this.message
      ? `<div class="wb-msg ${this.message.error ? "blocked" : "change"}">${esc(this.message.text)}</div>` : "";
    this.detail.innerHTML = `
      <div class="tk-head"><span class="tk-id">#${esc(t.id)}</span><span class="tk-st ${cls}">${esc(label)}</span>
        <span class="muted">rules v${esc(t.rulesVersion)}</span></div>
      <pre class="ai-sql"><code>${highlight(t.targetSql)}</code></pre>
      ${t.reason ? `<div class="tk-line"><span class="muted">사유</span> ${esc(t.reason)}</div>` : ""}
      ${findings ? `<ul class="tk-findings">${findings}</ul>` : ""}
      ${t.aiOpinion ? `<div class="tk-line"><span class="muted">AI 1차 소견</span> ${esc(t.aiOpinion)}</div>` : ""}
      ${t.verifySql ? `<div class="tk-line muted">검증 조회</div><pre class="ai-sql"><code>${highlight(t.verifySql)}</code></pre>` : ""}
      <ol class="tk-steps">${steps}</ol>
      ${this.actions(t)}
      ${message}
      <h4 class="tk-sub">실행 기록 <span class="muted">${this.executions.length}건</span></h4>
      ${this.executions.length
        ? this.executions.map((x, i) => this.execution(x, i === 0)).join("")
        : '<div class="muted">아직 드라이런·실행 기록이 없습니다. 드라이런은 실제로 실행한 뒤 롤백해 바뀔 행을 보여줍니다.</div>'}`;
  }

  actions(t) {
    const button = (act, text, cls = "") =>
      `<button class="btn btn-small ${cls}" data-act="${act}">${esc(this.armed === act ? `${text} 확인(한 번 더)` : text)}</button>`;
    const list = [];
    if (this.isAdmin()) {
      if (t.status === "PENDING") list.push(button("dry-run", "드라이런"), button("approve", "승인", "btn-primary"), button("reject", "반려", "btn-danger"));
      if (t.status === "APPROVED") list.push(button("dry-run", "드라이런"), button("execute", "실행", "btn-primary"));
      if (t.status === "EXECUTED") list.push(button("revert-dry-run", "되돌리기 드라이런"), button("revert", "되돌리기", "btn-danger"));
      if (this.captureHint && t.status === "PENDING") list.push(button("dry-run-raw", "캡처 없이 드라이런"));
      if (this.captureHint && t.status === "APPROVED") {
        list.push(button("dry-run-raw", "캡처 없이 드라이런"), button("execute-raw", "캡처 없이 실행(되돌리기 불가)", "btn-danger"));
      }
    }
    list.push('<button class="btn btn-small" data-act="to-editor">편집기로</button>');
    const hints = [];
    if (!this.isAdmin()) hints.push("드라이런·승인·실행·되돌리기는 ADMIN만 합니다.");
    if (["EXECUTING", "ROLLING_BACK"].includes(t.status)) {
      hints.push("실행 중이거나 커밋 여부를 확인하지 못한 상태입니다. 같은 변경이 두 번 나가지 않게 막아 두었으니, 대상 행을 직접 확인한 뒤 정리해야 합니다.");
    }
    return `<div class="tk-actions">${list.join("")}
      ${t.status === "PENDING" && this.isAdmin() ? '<input class="tk-comment" placeholder="결정 코멘트(선택)">' : ""}</div>
      ${hints.map((h) => `<div class="hint">${esc(h)}</div>`).join("")}`;
  }

  execution(x, open) {
    const [outcome, ocls] = OUTCOME[x.outcome] || [x.outcome, ""];
    let rollback = "";
    if (x.action === "DRY_RUN") {
      rollback = x.rollbackAvailable ? "실행하면 이 사본으로 되돌릴 수 있습니다." : `실행해도 사본으로 되돌릴 수 없습니다: ${x.rollbackNote || "사본 없음"}`;
    } else if (x.action === "EXECUTE" && x.outcome === "COMMITTED") {
      rollback = x.rollbackAvailable
        ? `되돌리기 가능 · 사본 보존 ${time(x.imagesExpireAt).slice(0, 16)}까지 · ${x.imagesEncrypted ? "암호화 저장" : "암호화 키가 없어 평문 저장"}`
        : `되돌리기 불가: ${x.rollbackNote || "사본 없음"}`;
    }
    const detail = Array.isArray(x.detail)
      ? `<ul class="tk-conflicts">${x.detail.map((c) => `<li>키 ${esc((c.keyValues || []).join(", "))}: ${esc(c.reason)}${(c.changedColumns || []).length ? ` (${esc(c.changedColumns.join(", "))})` : ""}</li>`).join("")}</ul>`
      : x.detail ? `<pre class="tk-error">${esc(x.detail)}</pre>` : "";
    const rows = !x.rowChanges ? ""
      : x.rowChanges.diff ? renderDiff(x.rowChanges.diff, { maskedColumns: x.rowChanges.maskedColumns })
        : `<div class="muted">${esc(x.rowChanges.unavailable)}</div>`;
    const workload = x.action === "EXECUTE" && x.outcome === "COMMITTED"
      ? `<button class="btn btn-small" data-act="workload" data-exec="${esc(x.id)}">실행 전후 워크로드 비교(60분)</button><div class="tk-workload" data-workload="${esc(x.id)}"></div>` : "";
    return `<details class="tk-exec" ${open ? "open" : ""}>
      <summary><span class="tk-act">${esc(ACTION[x.action] || x.action)}</span><span class="tk-out ${ocls}">${esc(outcome)}</span>
        <span class="muted">${esc(x.affectedRows ?? "-")}행 · ${esc(x.kind)} · ${esc(x.principal)} · ${esc(time(x.startedAt))} · sha ${esc((x.statementSha256 || "").slice(0, 10))}</span></summary>
      ${rollback ? `<div class="tk-line">${esc(rollback)}</div>` : ""}
      ${detail}${rows}${x.schemaDiff ? renderSchemaDiff(x.schemaDiff) : ""}${x.probe ? renderProbe(x.probe) : ""}${workload}
    </details>`;
  }

  async onClick(e) {
    const btn = e.target.closest("button[data-act]");
    const t = this.ticket();
    if (!btn || !t) return;
    const act = btn.dataset.act;
    if (act === "to-editor") {
      this.onOpenSql(t.targetSql);
      return;
    }
    if (act === "workload") {
      await this.workload(btn.dataset.exec);
      return;
    }
    if (ARMED.has(act) && this.armed !== act) {
      this.armed = act;
      this.render();
      clearTimeout(this.armTimer);
      this.armTimer = setTimeout(() => {
        this.armed = null;
        this.render();
      }, 4000);
      return;
    }
    this.armed = null;
    const comment = this.detail.querySelector(".tk-comment")?.value || "";
    await this.run(t, act, comment);
  }

  async run(t, act, comment) {
    const id = encodeURIComponent(t.id);
    const calls = {
      "dry-run": [`/api/workbench/tickets/${id}/dry-run`, { withoutCapture: false }],
      "dry-run-raw": [`/api/workbench/tickets/${id}/dry-run`, { withoutCapture: true }],
      execute: [`/api/workbench/tickets/${id}/execute`, { withoutCapture: false }],
      "execute-raw": [`/api/workbench/tickets/${id}/execute`, { withoutCapture: true }],
      "revert-dry-run": [`/api/workbench/tickets/${id}/revert`, { dryRun: true }],
      revert: [`/api/workbench/tickets/${id}/revert`, { dryRun: false }],
      approve: [`/api/reviews/${id}/decision`, { approved: true, comment }],
      reject: [`/api/reviews/${id}/decision`, { approved: false, comment }],
    };
    const [path, body] = calls[act];
    this.message = { text: act.startsWith("approve") || act === "reject" ? "결정을 기록하는 중..." : "대상 DB에서 처리하는 중..." };
    this.render();
    try {
      const res = await request(path, { method: "POST", body });
      this.message = res.action
        ? { text: `${ACTION[res.action] || res.action}: ${(OUTCOME[res.outcome] || [res.outcome])[0]} · ${res.affectedRows ?? 0}행` }
        : { text: act === "approve" ? "승인했습니다. 이제 실행할 수 있습니다." : "반려했습니다." };
      this.captureHint = false;
    } catch (e) {
      this.message = { error: true, text: e.message };
      if (e instanceof ApiError && e.status === 409 && e.message.includes("캡처 없이")) this.captureHint = true;
    }
    await this.refresh();
  }

  async workload(executionId) {
    const box = this.detail.querySelector(`[data-workload="${CSS.escape(executionId)}"]`);
    if (!box) return;
    box.innerHTML = '<div class="muted">스냅샷 구간을 비교하는 중...</div>';
    try {
      box.innerHTML = renderWorkload(await request(`/api/workbench/executions/${encodeURIComponent(executionId)}/workload?windowMinutes=60`));
    } catch (e) {
      box.innerHTML = `<div class="muted">워크로드 비교 실패: ${esc(e.message)}</div>`;
    }
  }
}
