import { isAbsolute, relative, resolve } from "node:path";

const SHELL_META = /[;&|<>`\r\n]|\$\(|\$\{/;
const FORBIDDEN_COMMAND = /\b(?:git\s+(?:push|reset|clean|checkout|switch|commit)|kubectl|helm|terraform\s+(?:apply|destroy)|docker\s+compose\s+(?:up|down|rm)|(?:mysql|psql|sqlcmd|sqlplus|mongosh)\b|(?:drop|truncate|insert|update|delete)\s+(?:database|schema|table|from|into|\w+))/i;
const SAFE_COMMAND = /^(?:pwd|whoami|date|(?:ls|find|rg|grep|head|tail|sed|awk|wc|file|sips|pdfinfo|pdftotext|node\s+--check|npm\s+(?:test|run\s+(?:lint|check|test))|git\s+(?:status|diff|log|show|branch\s+--show-current)|docker\s+(?:ps|inspect|logs)|docker\s+compose\s+config|\.\/gradlew\s+(?:compileJava|test|check|bootJar)|\.\/scripts\/check-conventions\.sh)(?:\s|$))/i;
const APPROVAL_ID = /^[A-Za-z][A-Za-z0-9._:-]{2,80}$/;
const UNSAFE_PATH_ARGUMENT = /(?:^|[\s=])(?:\/|~\/|\.\.\/)/;
const PROTECTED_PATH_ARGUMENT = /(?:^|[\s=])(?:\.git(?:\/|$)|\.env(?:\.|$)|backups(?:\/|$)|build(?:\/|$)|data(?:\/|$))/;

export const POLICIES = Object.freeze({
  readonly: Object.freeze({ allowEdit: false, allowBash: false }),
  change: Object.freeze({ allowEdit: true, allowBash: true }),
});

export function normalizePolicy(name = "readonly") {
  return Object.hasOwn(POLICIES, name) ? name : "readonly";
}

export function isInside(root, candidate) {
  const base = resolve(root);
  const target = resolve(base, candidate);
  const rel = relative(base, target);
  return rel === "" || (!rel.startsWith("..") && !isAbsolute(rel));
}

export function protectedPath(root, candidate) {
  if (!isInside(root, candidate)) return true;
  const rel = relative(resolve(root), resolve(root, candidate));
  return /^(?:\.git(?:\/|$)|\.env(?:\.|$)|backups(?:\/|$)|build(?:\/|$)|data(?:\/|$))/.test(rel);
}

export function redactForAudit(value) {
  return String(value ?? "")
    .replace(/((?:PASSWORD|TOKEN|SECRET|API[_-]?KEY|PRIVATE[_-]?KEY)\s*[=:]\s*)[^\s'"`]+/gi, "$1<redacted>")
    .replace(/(['"](?:password|token|secret|apiKey|privateKey)['"]\s*:\s*['"])[^'"]+(['"])/gi, "$1<redacted>$2");
}

function inputPath(input) {
  return input?.path ?? input?.file ?? input?.filename ?? null;
}

export function evaluateToolCall({ policy = "readonly", root, approvalId = "", toolName, input = {} }) {
  const selected = normalizePolicy(policy);
  const path = inputPath(input);
  if (["read", "grep", "find", "ls"].includes(toolName)) {
    if (path && protectedPath(root, path)) return { allow: false, reason: "보호된 경로 또는 작업공간 밖의 경로입니다" };
    return { allow: true, reason: "읽기 도구 허용" };
  }
  if (["edit", "write"].includes(toolName)) {
    if (!POLICIES[selected].allowEdit) return { allow: false, reason: "readonly 정책에서는 파일 변경을 허용하지 않습니다" };
    if (!path || protectedPath(root, path)) return { allow: false, reason: "보호된 경로 또는 작업공간 밖의 파일 변경입니다" };
    if (!APPROVAL_ID.test(approvalId)) return { allow: false, reason: "파일 변경에는 사람이 지정한 DBTOWER_PI_APPROVAL_ID가 필요합니다" };
    return { allow: true, reason: `승인 ${approvalId}로 파일 변경 허용` };
  }
  if (toolName === "bash") {
    const command = String(input.command ?? "").trim();
    if (!POLICIES[selected].allowBash) return { allow: false, reason: "readonly 정책에서는 셸 실행을 허용하지 않습니다" };
    if (SHELL_META.test(command)) return { allow: false, reason: "셸 연결자·리다이렉션·명령 치환은 허용하지 않습니다" };
    if (UNSAFE_PATH_ARGUMENT.test(command)) return { allow: false, reason: "작업공간 밖을 가리킬 수 있는 경로 인자입니다" };
    if (PROTECTED_PATH_ARGUMENT.test(command)) return { allow: false, reason: "보호된 경로를 가리키는 명령입니다" };
    if (FORBIDDEN_COMMAND.test(command)) return { allow: false, reason: "배포·운영 DB·위험한 git 명령은 Pi 실행 정책에서 차단합니다" };
    if (!SAFE_COMMAND.test(command)) return { allow: false, reason: "허용 목록에 없는 셸 명령입니다" };
    return { allow: true, reason: "허용 목록의 읽기·검증 명령" };
  }
  return { allow: false, reason: `정책에 등록되지 않은 도구 ${toolName}입니다` };
}

export function isTestCommand(command = "") {
  return /(?:\.\/gradlew|gradlew|npm\s+run|npm\s+test|pytest|mvn\s+test|cargo\s+test).*\b(?:test|check|verify|lint)\b/i.test(command)
    || /\.\/scripts\/check-conventions\.sh/i.test(command);
}
