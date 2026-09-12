// 변경 티켓 — 리뷰 게이트의 요청을 워크벤치에서 드라이런·승인·실행·되돌리기까지 잇고, 실행마다 전후 비교를 보여준다.
// 상태 전이와 판정은 전부 서버(리뷰 게이트·실행 계층)가 한다. 이 화면은 상태에 맞는 버튼을 보여줄 뿐이다.

import { request, esc, ApiError, localTime } from "./api.js";
import { highlight } from "./editor.js";
import { renderDiff, renderSchemaDiff, renderProbe, renderWorkload } from "./diff.js";

const STATUS = {
  PENDING: ["대기", "st-pending"],
  APPROVED: ["승인", "st-approved"],
  REJECTED: ["반려", "st-rejected"],
  CANCELLED: ["취소", "st-rolled"],
  EXECUTING: ["실행 중", "st-running"],
  EXECUTED: ["실행됨", "st-executed"],
  ROLLING_BACK: ["되돌리는 중", "st-running"],
  ROLLED_BACK: ["되돌림", "st-rolled"],
};
const ACTION = { DRY_RUN: "드라이런", EXECUTE: "실행", REVERT_DRY_RUN: "되돌리기 드라이런", REVERT: "되돌리기", RESOLVE: "확인 뒤 정리" };
const OUTCOME = {
  RUNNING: ["진행 중(결과 미확인)", "o-warn"],
  COMMITTED: ["커밋", "o-ok"],
  ROLLED_BACK: ["롤백(흔적 없음)", "o-info"],
  FAILED: ["실패(롤백됨)", "o-bad"],
  CONFLICT: ["충돌(아무것도 쓰지 않음)", "o-warn"],
  UNCERTAIN: ["커밋 여부 불명", "o-bad"],
};
// 되돌릴 수 없거나 대상 DB·티켓 상태에 흔적을 남기는 동작은 한 번 더 눌러야 나간다(브라우저 확인창 대신 버튼 자체로)
const ARMED = new Set(["execute", "execute-raw", "revert", "approve", "cancel", "resolve-applied", "resolve-not-applied"]);
const OPEN = ["PENDING", "APPROVED", "EXECUTING", "ROLLING_BACK"];
const time = (t) => localTime(t, { seconds: true });

// AI 소견은 "1. ... 2. ..."처럼 번호 문장이 한 줄로 온다 — 번호 앞에서 끊어 문단으로 만든다(내용은 그대로).
function aiParagraphs(text) {
  const parts = String(text).split(/(?=(?:^|\s)\d+\.\s)/).map((s) => s.trim()).filter(Boolean);
  return (parts.length > 1 ? parts : [String(text)]).map((p) => `<p>${esc(p)}</p>`).join("");
}

export class TicketPanel {
  constructor({ list, detail, count, can, me, onOpenSql, onProposeTicket }) {
    Object.assign(this, { list, detail, count, can, me, onOpenSql, onProposeTicket });
    this.instanceId = null;
    this.tickets = [];
    this.executions = [];
    this.proposals = [];
    this.selected = null;
    this.message = null;
    this.armed = null;
    this.captureHint = false;
    // 목록 범위(152절) — 끝난 티켓이 목록 대부분을 차지해, 처리할 건(대기·승인·실행 중)을 먼저 보인다.
    // null은 아직 고르지 않음: 열린 티켓이 있으면 열린 것만, 없으면 전체(빈 목록으로 기록을 가리지 않게)
    this.scope = null;
    list.addEventListener("click", (e) => {
      const scope = e.target.closest("[data-scope]");
      if (scope) {
        this.scope = scope.dataset.scope;
        this.drawList();
        return;
      }
      const li = e.target.closest("[data-ticket]");
      if (li) this.select(Number(li.dataset.ticket));
    });
    detail.addEventListener("click", (e) => this.onClick(e));
  }

  async load(instanceId, selectId) {
    this.instanceId = instanceId;
    this.selected = null;
    this.scope = null;
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
    // 응답을 기다리는 동안 다른 인스턴스를 고르면 앞 인스턴스의 목록이 새 목록을 덮었다(148절 감사) — 도착했을 때 같은 인스턴스일 때만 쓴다
    const instanceId = this.instanceId;
    let tickets;
    try {
      tickets = await request(`/api/instances/${encodeURIComponent(instanceId)}/reviews`);
    } catch (e) {
      if (this.instanceId !== instanceId) return false;
      this.list.innerHTML = `<div class="muted">티켓을 불러오지 못했습니다: ${esc(e.message)}</div>`;
      return false;
    }
    if (this.instanceId !== instanceId) return false;
    this.tickets = tickets;
    this.drawList();
    return true;
  }

  drawList() {
    const open = this.tickets.filter((t) => OPEN.includes(t.status));
    this.count.textContent = open.length || "";
    if (!this.tickets.length) {
      this.list.innerHTML = "";
      return;
    }
    // 고른 티켓이 끝난 것이면 전체로 넓힌다 — 딥링크로 연 티켓이나 방금 실행해 닫힌 티켓이 목록에서 사라져 보이지 않게
    const selected = this.ticket();
    let scope = this.scope ?? (open.length ? "open" : "all");
    if (scope === "open" && selected && !OPEN.includes(selected.status)) scope = this.scope = "all";
    const shown = scope === "open" ? open : this.tickets;
    const items = shown.map((t) => {
      const [label, cls] = STATUS[t.status] || [t.status, ""];
      return `<li data-ticket="${esc(t.id)}" class="${t.id === this.selected ? "active" : ""}">
        <span class="tk-id">#${esc(t.id)}</span><span class="tk-st ${cls}">${esc(label)}</span>
        <span class="tk-sql">${esc(t.targetSql.replace(/\s+/g, " ").slice(0, 90))}</span></li>`;
    }).join("");
    this.list.innerHTML = `<div class="tk-scope" role="group" aria-label="티켓 범위">
        <button type="button" class="tk-scope-btn" data-scope="open" aria-pressed="${scope === "open"}">열린 것 ${open.length}</button>
        <button type="button" class="tk-scope-btn" data-scope="all" aria-pressed="${scope === "all"}">전체 ${this.tickets.length}</button></div>
      ${items ? `<ul class="tk-items">${items}</ul>` : '<div class="muted tk-none">열린 티켓이 없습니다. 끝난 티켓은 "전체"에서 봅니다.</div>'}`;
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
    if (!(await this.reloadList())) return;
    const t = this.ticket();
    if (!t) return;
    let executions;
    try {
      executions = await request(`/api/workbench/tickets/${encodeURIComponent(t.id)}/executions`);
    } catch (e) {
      executions = [];
      this.message = { error: true, text: `실행 기록을 불러오지 못했습니다: ${e.message}` };
    }
    // 기다리는 동안 다른 티켓을 골랐으면 늦게 온 실행 기록으로 그 티켓을 그리지 않는다
    if (this.selected !== t.id) return;
    this.executions = executions;
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
      t.intervenedBy ? `사람 개입(${t.status === "CANCELLED" ? "취소" : "확인 뒤 정리"}) ${esc(t.intervenedBy)} · ${esc(time(t.intervenedAt))}${t.interventionNote ? " · " + esc(t.interventionNote) : ""}` : null,
    ].filter(Boolean).map((s) => `<li>${s}</li>`).join("");
    const message = this.message
      ? `<div class="wb-msg ${this.message.error ? "blocked" : "change"}">${esc(this.message.text)}</div>` : "";
    this.proposals = [];
    this.detail.innerHTML = `
      <div class="tk-head"><span class="tk-id">#${esc(t.id)}</span><span class="tk-st ${cls}">${esc(label)}</span></div>
      <pre class="ai-sql"><code>${highlight(t.targetSql)}</code></pre>
      ${t.reason ? `<div class="tk-block"><div class="tk-label">사유</div><div class="tk-body">${esc(t.reason)}</div></div>` : ""}
      ${findings ? `<div class="tk-block tk-rules"><div class="tk-label">규칙 판정 <span class="muted">rules v${esc(t.rulesVersion)}</span></div>
        <ul class="tk-findings">${findings}</ul></div>` : ""}
      ${t.aiOpinion ? `<div class="tk-block tk-ai"><div class="tk-label">AI 1차 소견 <span class="muted">판단은 사람이 한다</span></div>
        <div class="tk-body">${aiParagraphs(t.aiOpinion)}</div></div>` : ""}
      ${t.verifySql ? `<div class="tk-block"><div class="tk-label">검증 조회</div>
        <pre class="ai-sql"><code>${highlight(t.verifySql)}</code></pre></div>` : ""}
      <ol class="tk-steps">${steps}</ol>
      ${this.actions(t)}
      ${message}
      <h4 class="tk-sub">실행 기록 <span class="muted">${this.executions.length}건</span></h4>
      ${this.executions.length
        ? this.executions.map((x, i) => this.execution(t, x, i === 0)).join("")
        : '<div class="muted">아직 드라이런·실행 기록이 없습니다. 드라이런은 실제로 실행한 뒤 롤백해 바뀔 행을 보여줍니다.</div>'}`;
  }

  actions(t) {
    const button = (act, text, cls = "") =>
      `<button class="btn btn-small ${cls}" data-act="${act}">${esc(this.armed === act ? `${text} 확인(한 번 더)` : text)}</button>`;
    // 버튼은 능력으로 가른다(/api/me capabilities). 승인자는 승인·반려·드라이런까지, 운영자는 드라이런·실행·되돌리기까지 —
    // 한 사람이 승인하고 실행까지 하는 흐름을 화면에서도 만들지 않는다(관리자는 둘 다 가진다). 최종 인가는 서버가 한다
    const approve = this.can("CHANGE_APPROVE");
    const dryRun = this.can("CHANGE_DRY_RUN");
    const execute = this.can("CHANGE_EXECUTE");
    const mine = this.me() === t.requester;
    const closer = mine || approve || execute;
    const list = [];
    if (t.status === "PENDING") {
      if (dryRun) list.push(button("dry-run", "드라이런"));
      if (approve) list.push(button("approve", "승인", "btn-primary"), button("reject", "반려", "btn-danger"));
      if (dryRun && this.captureHint) list.push(button("dry-run-raw", "캡처 없이 드라이런"));
    }
    if (t.status === "APPROVED") {
      if (dryRun) list.push(button("dry-run", "드라이런"));
      if (execute) list.push(button("execute", "실행", "btn-primary"));
      if (dryRun && this.captureHint) list.push(button("dry-run-raw", "캡처 없이 드라이런"));
      if (execute && this.captureHint) list.push(button("execute-raw", "캡처 없이 실행(되돌리기 불가)", "btn-danger"));
    }
    if (t.status === "EXECUTED" && execute) list.push(button("revert-dry-run", "되돌리기 드라이런"), button("revert", "되돌리기", "btn-danger"));
    if (closer && ["PENDING", "APPROVED"].includes(t.status)) list.push(button("cancel", "티켓 취소"));
    list.push('<button class="btn btn-small" data-act="to-editor">편집기로</button>');
    const hints = [];
    if (t.status === "PENDING" && !approve) hints.push("승인·반려는 승인자(APPROVER)가 합니다.");
    if (t.status === "APPROVED" && !execute) hints.push("실행은 운영자(OPERATOR)가 합니다. 승인한 사람과 실행하는 사람을 나눕니다.");
    if (t.status === "EXECUTED" && !execute) hints.push("되돌리기는 운영자(OPERATOR)가 합니다.");
    if (mine && !approve && !execute && ["PENDING", "APPROVED"].includes(t.status)) hints.push("요청자는 대기·승인 상태의 자기 티켓을 취소할 수 있습니다.");
    let resolve = "";
    if (["EXECUTING", "ROLLING_BACK"].includes(t.status)) {
      hints.push("실행 중이거나 커밋 여부를 확인하지 못한 상태입니다. 같은 변경이 두 번 나가지 않게 막아 두었습니다. "
        + "대상 행을 직접 확인한 뒤, 무엇으로 확인했는지 적고 결과를 고르세요.");
      if (execute) {
        resolve = `<div class="tk-actions"><input class="tk-resolve-note" placeholder="확인 근거(예: root로 id=3 조회, grade=VIP 확인)">
          ${button("resolve-applied", "반영됨으로 정리")}${button("resolve-not-applied", "반영 안 됨으로 정리")}</div>`;
      }
    }
    const comment = ["PENDING", "APPROVED"].includes(t.status) && closer
      ? '<input class="tk-comment" placeholder="코멘트·취소 사유(선택)">' : "";
    return `<div class="tk-actions">${list.join("")}${comment}</div>${resolve}
      ${hints.map((h) => `<div class="hint">${esc(h)}</div>`).join("")}`;
  }

  execution(t, x, open) {
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
      ? `<button class="btn btn-small" data-act="workload" data-exec="${esc(x.id)}">실행 전후 워크로드 비교(60분)</button>`
        // 운영자가 변경 직후 관제 화면으로 넘어가는 입구 — 대시보드가 실행 시각 앞뒤 30분을 시점 비교로 바로 연다(app.js handleInstanceDeepLink)
        + `<a class="btn btn-small" href="/?instance=${esc(encodeURIComponent(t.instanceId))}&amp;compareAt=${esc(encodeURIComponent(x.startedAt))}" target="_blank" rel="noopener">대시보드에서 전후 Top Query 비교</a>`
        + `<div class="tk-workload" data-workload="${esc(x.id)}"></div>` : "";
    return `<details class="tk-exec" ${open ? "open" : ""}>
      <summary><span class="tk-act">${esc(ACTION[x.action] || x.action)}</span><span class="tk-out ${ocls}">${esc(outcome)}</span>
        <span class="muted">${esc(x.affectedRows ?? "-")}행 · ${esc(x.kind)} · ${esc(x.principal)} · ${esc(time(x.startedAt))} · sha ${esc((x.statementSha256 || "").slice(0, 10))}</span></summary>
      ${rollback ? `<div class="tk-line">${esc(rollback)}</div>` : ""}
      ${detail}${rows}${x.schemaDiff ? renderSchemaDiff(x.schemaDiff) : ""}${x.probe ? renderProbe(x.probe) : ""}
      ${this.inverse(t, x)}${workload}
    </details>`;
  }

  /** DDL 역변경 제안 — 실행 버튼이 아니라 "새 티켓으로 올리기"다. 역변경도 드라이런·승인을 다시 거친다 */
  inverse(t, x) {
    if (!x.inverse) return "";
    const items = (x.inverse.statements || []).map((sql) => {
      const i = this.proposals.push({ sql, reason: `티켓 #${t.id} 역변경` }) - 1;
      return `<li><pre class="ai-sql"><code>${highlight(sql)}</code></pre>
        <button class="btn btn-small" data-act="propose" data-i="${i}">역변경 티켓으로 올리기</button></li>`;
    }).join("");
    return `<div class="df-title">역변경 제안</div>
      ${items ? `<ul class="tk-inverse">${items}</ul>` : ""}
      ${x.inverse.note ? `<div class="hint">${esc(x.inverse.note)}</div>` : ""}`;
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
    if (act === "propose") {
      const p = this.proposals[Number(btn.dataset.i)];
      if (p) this.onProposeTicket(p.sql, p.reason);
      return;
    }
    if (act === "workload") {
      await this.workload(btn.dataset.exec);
      return;
    }
    if (ARMED.has(act) && this.armed !== act) {
      // 다시 그리면 입력칸이 초기화되므로 적어 둔 메모를 보존한다
      const kept = { comment: this.detail.querySelector(".tk-comment")?.value, note: this.detail.querySelector(".tk-resolve-note")?.value };
      this.armed = act;
      this.render();
      this.restoreInputs(kept);
      clearTimeout(this.armTimer);
      this.armTimer = setTimeout(() => {
        const again = { comment: this.detail.querySelector(".tk-comment")?.value, note: this.detail.querySelector(".tk-resolve-note")?.value };
        this.armed = null;
        this.render();
        this.restoreInputs(again);
      }, 4000);
      return;
    }
    this.armed = null;
    const comment = this.detail.querySelector(".tk-comment")?.value || "";
    const note = this.detail.querySelector(".tk-resolve-note")?.value || "";
    await this.run(t, act, comment, note);
  }

  restoreInputs({ comment, note }) {
    const c = this.detail.querySelector(".tk-comment");
    const n = this.detail.querySelector(".tk-resolve-note");
    if (c && comment) c.value = comment;
    if (n && note) n.value = note;
  }

  async run(t, act, comment, note) {
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
      cancel: [`/api/reviews/${id}/cancel`, { note: comment }],
      "resolve-applied": [`/api/workbench/tickets/${id}/resolve`, { applied: true, note }],
      "resolve-not-applied": [`/api/workbench/tickets/${id}/resolve`, { applied: false, note }],
    };
    const [path, body] = calls[act];
    const decision = ["approve", "reject", "cancel"].includes(act) || act.startsWith("resolve");
    this.message = { text: decision ? "기록하는 중..." : "대상 DB에서 처리하는 중..." };
    this.render();
    try {
      const res = await request(path, { method: "POST", body });
      if (res.action === "RESOLVE") {
        this.message = { text: res.detail };
      } else if (res.action) {
        this.message = { text: `${ACTION[res.action] || res.action}: ${(OUTCOME[res.outcome] || [res.outcome])[0]} · ${res.affectedRows ?? 0}행` };
      } else {
        this.message = { text: { approve: "승인했습니다. 이제 실행할 수 있습니다.", reject: "반려했습니다.", cancel: "티켓을 취소했습니다." }[act] };
      }
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
