#!/bin/sh
# DBHub MCP 서버 실행기 — MCP 클라이언트(Claude Code 등)가 이 스크립트를 직접 띄운다.
#
# 등록:  claude mcp add dbhub -- sh /절대경로/dbhub/scripts/dbhub-mcp.sh
# 준비:  ./gradlew writeMcpClasspath   (클래스패스 파일 생성 — 코드 변경 시 재실행)
# 대상:  환경변수 DBHUB_URL (기본 http://localhost:8080) — DBHub 앱이 떠 있어야 한다
#
# stdout은 JSON-RPC 전용이므로 어떤 로그도 stdout에 쓰지 않는다.
DIR="$(cd "$(dirname "$0")/.." && pwd)"
CP_FILE="$DIR/build/mcp-classpath.txt"
if [ ! -f "$CP_FILE" ]; then
  echo "build/mcp-classpath.txt가 없습니다 — 먼저 ./gradlew writeMcpClasspath 를 실행하세요" >&2
  exit 1
fi
exec java -cp "$(cat "$CP_FILE")" io.dbhub.mcp.McpStdioServer
