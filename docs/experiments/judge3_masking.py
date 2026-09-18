"""세 번째 판정자 — 같은 기준표로 로컬 모델 하나를 더 돌린다(#131).

두 판정(이 세션·qwen3:14b)의 일치가 126칸 중 77칸(61%)뿐이라, 그 상태로는 기본값을 바꿀 근거가 서지 않는다.
판정자를 하나 더 두고 다수결이 갈리는 칸만 사람이 본다.

판정자는 셋 다 모델이다. 이 사실을 결과보다 먼저 적는다 — 다수결이 "정답"을 뜻하지 않고,
"세 판정이 같은 곳"이 상대적으로 믿을 만하다는 뜻일 뿐이다.

사용법: python3 judge3.py <levels.jsonl> <cases.json> <출력.jsonl> [모델]
"""
import json
import re
import subprocess
import sys
import time
from pathlib import Path

RUBRIC = """너는 SQL 성능 진단 응답을 채점하는 채점자다. 응답을 고치거나 새로 진단하지 말고 라벨만 정한다.

기대 원인: {cause}

라벨 정의:
- confirmed: 응답이 기대 원인을 이 쿼리의 원인(또는 풀스캔이 정상인 이유)으로 분명히 지목했다.
- candidate: 기대 원인을 가능성·후보로만 언급하고 정보가 없어 확정할 수 없다고 했다.
- missed: 기대 원인을 지목하지 않았거나 다른 원인으로 결론 냈다.

반드시 JSON 한 줄만 출력한다: {{"label": "confirmed|candidate|missed"}}

--- 응답 시작 ---
{response}
--- 응답 끝 ---"""


def judge(model, cause, response):
    prompt = RUBRIC.format(cause=cause, response=response[:4000])
    out = subprocess.run(
        ["ollama", "run", model, "--hidethinking"],
        input=prompt, capture_output=True, text=True, timeout=300)
    text = out.stdout.strip()
    m = re.search(r'\{[^{}]*"label"[^{}]*\}', text, re.S)
    if not m:
        return "unparsed", text[:200]
    try:
        return json.loads(m.group(0)).get("label", "unparsed"), ""
    except json.JSONDecodeError:
        return "unparsed", text[:200]


def main():
    levels, cases_path, out_path = Path(sys.argv[1]), Path(sys.argv[2]), Path(sys.argv[3])
    model = sys.argv[4] if len(sys.argv) > 4 else "llama3.1:8b"
    cases = {c["id"]: c for c in json.loads(cases_path.read_text())["cases"]}

    done = set()
    if out_path.exists():
        for line in out_path.read_text().splitlines():
            if line.strip():
                n = json.loads(line)
                done.add((n["caseId"], n["condition"], n["rep"]))

    rows = [json.loads(l) for l in levels.read_text().splitlines() if l.strip()]
    todo = [r for r in rows
            if (r["caseId"], r["condition"], r["rep"]) not in done
            and r.get("error") is None
            and r["caseId"] in cases]
    print(f"판정 대상 {len(todo)}건 (모델 {model})", flush=True)

    with out_path.open("a") as f:
        for i, r in enumerate(todo, 1):
            t0 = time.time()
            label, note = judge(model, cases[r["caseId"]]["expectedCause"], r["response"])
            f.write(json.dumps({"caseId": r["caseId"], "condition": r["condition"], "rep": r["rep"],
                                "label": label, "judgeModel": model, "note": note},
                               ensure_ascii=False) + "\n")
            f.flush()
            print(f"{i}/{len(todo)} {r['caseId']}/{r['condition']}/{r['rep']} {label} "
                  f"({time.time() - t0:.1f}s)", flush=True)


if __name__ == "__main__":
    main()
