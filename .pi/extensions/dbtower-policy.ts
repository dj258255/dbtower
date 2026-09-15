import { appendFileSync, mkdirSync } from "node:fs";
import { resolve } from "node:path";
import type { ExtensionAPI } from "@mariozechner/pi-coding-agent";
import {
  evaluateToolCall,
  isTestCommand,
  normalizePolicy,
  redactForAudit,
} from "../../scripts/pi-policy.mjs";

/**
 * Pi의 모델 지침을 실제 도구 호출 앞에서 다시 검사한다.
 * DBTower 서버의 인증·변경 티켓·읽기 전용 경계가 최종 권위이며, 이 확장은 로컬 에이전트의 사전 방어선이다.
 */
export default function dbtowerPolicy(pi: ExtensionAPI) {
  pi.registerFlag("dbtower-policy", {
    description: "DBTower Pi policy: readonly or change",
    type: "string",
    default: process.env.DBTOWER_PI_POLICY ?? "readonly",
  });

  const policy = normalizePolicy(String(pi.getFlag("dbtower-policy") ?? "readonly"));
  const root = resolve(process.cwd());
  const approvalId = process.env.DBTOWER_PI_APPROVAL_ID ?? "";
  const auditPath = resolve(root, ".pi/policy-audit.ndjson");
  const pendingBash = new Map<string, string>();
  let changed = false;
  let testsPassed = false;
  let reminderQueued = false;

  const audit = (record: Record<string, unknown>) => {
    mkdirSync(resolve(root, ".pi"), { recursive: true });
    appendFileSync(auditPath, `${JSON.stringify({
      at: new Date().toISOString(),
      policy,
      ...record,
    })}\n`, { encoding: "utf8", mode: 0o600 });
  };

  pi.on("session_start", async (_event, ctx) => {
    ctx.ui.setStatus("dbtower-policy", `policy:${policy}`);
    ctx.ui.notify(`DBTower Pi 정책 ${policy} 적용 · 서버 게이트가 최종 권위`, "info");
    audit({ action: "session_start", outcome: "allow" });
  });

  pi.on("before_agent_start", async (event) => ({
    systemPrompt: `${event.systemPrompt}\n\n[DBTower Pi policy: ${policy}] 도구 호출 전 실행 정책이 경로·명령·승인·테스트 상태를 검사한다. 차단을 우회하려고 셸 연결자나 다른 도구를 조합하지 말고, 차단 이유를 사용자에게 설명한다. DBTower 서버의 인증과 변경 티켓이 최종 권위다.`,
  }));

  pi.on("tool_call", async (event, ctx) => {
    const input = (event as { input?: Record<string, unknown> }).input ?? {};
    const decision = evaluateToolCall({ policy, root, approvalId, toolName: event.toolName, input });
    const command = event.toolName === "bash" ? redactForAudit(input.command) : undefined;
    audit({
      action: "tool_call",
      tool: event.toolName,
      path: redactForAudit(input.path ?? input.file),
      command,
      outcome: decision.allow ? "allow" : "block",
      reason: decision.reason,
    });
    if (!decision.allow) {
      ctx.ui.notify(`차단: ${decision.reason}`, "warning");
      return { block: true, reason: decision.reason };
    }
    if (event.toolName === "edit" || event.toolName === "write") {
      changed = true;
      testsPassed = false;
      reminderQueued = false;
    }
    if (event.toolName === "bash") pendingBash.set(event.toolCallId, String(input.command ?? ""));
    return undefined;
  });

  pi.on("tool_execution_end", async (event) => {
    if (event.toolName !== "bash") return;
    const command = pendingBash.get(event.toolCallId) ?? "";
    pendingBash.delete(event.toolCallId);
    if (isTestCommand(command) && !event.isError) {
      testsPassed = true;
      audit({ action: "test_gate", outcome: "pass", command: redactForAudit(command) });
    }
  });

  pi.on("turn_end", async (_event, ctx) => {
    if (!changed || testsPassed || reminderQueued) return;
    reminderQueued = true;
    audit({ action: "completion_gate", outcome: "block", reason: "변경 후 검증 명령 필요" });
    ctx.ui.notify("변경 후 테스트가 실행되지 않아 완료를 승인하지 않았습니다", "warning");
    pi.sendMessage({
      customType: "dbtower-policy",
      content: "정책 게이트: 파일 변경 후 성공한 테스트·검증 명령이 없습니다. 완료를 선언하기 전에 `./gradlew test` 또는 저장소에 맞는 검증 명령을 실행하고 결과를 확인하세요.",
      display: true,
    }, { deliverAs: "followUp", triggerTurn: true });
  });
}
