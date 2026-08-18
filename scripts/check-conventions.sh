#!/usr/bin/env bash
# AGENTS.md가 선언한 규약을 빌드가 실제로 강제하게 한다.
#
# 다관점 리뷰에서 드러난 문제: 규약 대부분이 실제로 지켜지고 있었지만 그건 사람의 규율이었고,
# 강제 장치가 없는 경계에서만 무너져 있었다(모듈 외부 리포지토리 참조 14파일, 기종 분기 30곳,
# 주석 우회가 뚫린 채 초록불인 테스트). 리팩토링보다 이 검사들이 값싸고 재발을 막는다.
#
# 실행: ./scripts/check-conventions.sh  (CI에서 test 앞단에 돈다)
set -uo pipefail
cd "$(dirname "$0")/.."

fail=0
report() {   # report <제목> <근거파일목록>
    if [ -n "$2" ]; then
        echo "실패: $1"
        echo "$2" | sed 's/^/    /'
        fail=1
    else
        echo "통과: $1"
    fi
}

# 1) JPA 엔티티 Lombok 규율 — @Data/@Setter/@ToString은 lazy 연관 지뢰와 무분별한 가변성을 부른다
hits=$(grep -rn --include="*.java" -E "^\s*@(Data|Setter|ToString|Builder)\b" src/main/java || true)
report "Lombok 규율 (@Data/@Setter/@ToString/@Builder 금지)" "$hits"

# 2) 모듈 경계 — 다른 모듈의 리포지토리·JPA 엔티티를 직접 참조하지 않는다(그 모듈의 공개 서비스로 우회).
#    Modulith의 modules.verify()는 순환과 internal 침범만 잡고, 모듈 루트에 놓인 리포지토리는
#    정당한 공개 API로 본다 — 그래서 이 검사가 따로 필요하다.
hits=""
for f in $(grep -rl --include="*.java" -E "^import io\.dbtower\.[a-z]+\.[A-Za-z]*Repository;" src/main/java || true); do
    owner=$(echo "$f" | sed -E 's|.*/io/dbtower/([a-z]+)/.*|\1|')
    imported=$(grep -oE "^import io\.dbtower\.([a-z]+)\.[A-Za-z]*Repository;" "$f" | sed -E 's|import io\.dbtower\.([a-z]+)\..*|\1|' | sort -u)
    for mod in $imported; do
        [ "$mod" = "$owner" ] || hits="${hits}${f}: ${mod} 모듈의 리포지토리를 직접 참조"$'\n'
    done
done
report "모듈 경계 (외부 모듈 리포지토리 직접 참조 금지)" "$(echo "$hits" | sed '/^$/d')"

# 3) 이모지 금지 — 코드·문서·커밋 메시지 전부. Discord 트리거 이모지는 기능 자체라 제외한다.
# grep -P는 macOS(BSD grep)에 없다 — 파이썬으로 이식 가능하게 검사한다.
hits=$(python3 - <<'EOF'
import pathlib, re, sys
# 범위는 app.js의 stripEmoji와 같게 둔다 — 화살표(U+2192)·체크(U+2713) 같은 기술 기호는
# 이모지가 아니라 보존 대상이다(2700-27BF Dingbats 제외).
EMOJI = re.compile("[\U0001F000-\U0001FAFF\u2600-\u26FF]")
SKIP = ("DiscordGatewayBot", "DiscordTriggerRules", "stripEmoji", "triggerEmojis")
for root in ("src/main/java", "src/main/resources/static"):
    for f in pathlib.Path(root).rglob("*"):
        if f.suffix not in (".java", ".js") or any(k in f.name for k in SKIP):
            continue
        for n, line in enumerate(f.read_text(encoding="utf-8", errors="ignore").splitlines(), 1):
            if EMOJI.search(line) and not any(k in line for k in SKIP):
                print(f"{f}:{n}: {line.strip()[:100]}")
EOF
)
report "이모지 금지" "$hits"

# 4) 기종 분기가 팩토리 밖으로 새는지 — 완전 금지는 현실적이지 않지만(Advisor.supports는 정당한
#    능력 선언이다), 새로 늘어나는 것은 눈에 보여야 한다. 기준선을 넘으면 실패시켜 의식적 결정을 강제한다.
BRANCH_BASELINE=32
count=$(grep -rn --include="*.java" -E "DbmsType\.(MYSQL|POSTGRESQL|MSSQL|ORACLE|MONGODB)" src/main/java \
        | grep -v "/operator/DbmsOperatorFactory.java\|/operator/internal/\|/registry/DbmsType.java" | wc -l | tr -d ' ')
if [ "$count" -gt "$BRANCH_BASELINE" ]; then
    report "기종 분기 기준선 (현재 ${count} > 기준 ${BRANCH_BASELINE})" \
        "새 기종 분기가 늘었다. DbmsOperator에 능력을 선언해 흡수할 수 있는지 먼저 검토하고, 불가피하면 기준선을 올려라."
else
    echo "통과: 기종 분기 기준선 (${count} <= ${BRANCH_BASELINE})"
fi

# 5) 읽기 전용 게이트가 주석을 걷어낸 뒤 판정하는지 — 리뷰에서 실제로 뚫렸던 지점이다.
#    두 게이트(requireSelect / LakehouseController)가 서로 다른 강도로 병존하지 않게 못 박는다.
hits=""
grep -q "canonical(sql)" src/main/java/io/dbtower/operator/internal/AbstractJdbcOperator.java \
    || hits="AbstractJdbcOperator.requireSelect가 canonical(주석·인용 제거) 사본으로 판정하지 않는다"
report "읽기 전용 게이트의 주석 인식" "$hits"

echo
[ "$fail" -eq 0 ] && echo "규약 검사 전부 통과" || echo "규약 검사 실패 — 위 항목을 확인하세요"
exit $fail
