import assert from "node:assert/strict";
import { evaluateToolCall, isInside, isTestCommand, normalizePolicy } from "./pi-policy.mjs";

const root = "/tmp/dbtower-policy-test";
const allow = (toolName, input = {}, extra = {}) => evaluateToolCall({ policy: "change", root, approvalId: "DBT-42", toolName, input, ...extra }).allow;
const block = (toolName, input = {}, extra = {}) => evaluateToolCall({ policy: "change", root, approvalId: "DBT-42", toolName, input, ...extra }).allow === false;

assert.equal(normalizePolicy("unknown"), "readonly");
assert.equal(isInside(root, "src/main.java"), true);
assert.equal(isInside(root, "../secrets"), false);
assert.equal(allow("read", { path: "src/main.java" }), true);
assert.equal(block("read", { path: ".env" }), true);
assert.equal(block("write", { path: "../outside.txt" }), true);
assert.equal(block("write", { path: "src/main.java" }, { approvalId: "" }), true);
assert.equal(allow("write", { path: "src/main.java" }), true);
assert.equal(allow("bash", { command: "./gradlew test" }), true);
assert.equal(block("bash", { command: "git push origin main" }), true);
assert.equal(block("bash", { command: "kubectl apply -f deploy.yml" }), true);
assert.equal(block("bash", { command: "./gradlew test; git push" }), true);
assert.equal(block("bash", { command: "./gradlew test\nrm -rf ." }), true);
assert.equal(block("bash", { command: "find /tmp -type f" }), true);
assert.equal(block("bash", { command: "git diff -- ../secrets" }), true);
assert.equal(block("bash", { command: "git diff -- .env" }), true);
assert.equal(block("bash", { command: "python deploy.py" }), true);
assert.equal(isTestCommand("./gradlew test --tests '*FooTest'"), true);
assert.equal(isTestCommand("git status"), false);
console.log("Pi policy tests: 19 passed");
