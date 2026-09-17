"""E2 응답 판정기.

1) calibrate: E2 1회차 응답 30건(L7·C4 제외)을 판정해 사람 판정(ai-masking-human-review.md)과 대조한다.
2) judge: 반복 응답(ai-masking-repeat.jsonl)과 1회차 B·C를 판정한다.

판정은 claude CLI(`claude -p`)가 한다. 모델 이름은 CLI 응답의 modelUsage에서 읽어 기록한다.
라벨: confirmed(기대 원인을 원인으로 지목) / candidate(후보로만 언급, 확정 못 함) / missed(지목 못 함)
"""
import concurrent.futures as cf
import json
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
EXP = ROOT / "docs" / "experiments"
CASES = {c["id"]: c for c in json.loads((ROOT / "src/test/resources/experiments/ai-masking-cases.json").read_text())["cases"]}
EXCLUDED = {"L7", "C4"}
MARK = {"✓": "confirmed", "△": "candidate", "✗": "missed"}

RUBRIC = """너는 SQL 성능 진단 응답을 채점하는 채점자다. 응답을 고치거나 새로 진단하지 말고 라벨만 정한다.

기대 원인: {cause}

라벨 정의:
- confirmed: 응답이 기대 원인을 이 쿼리의 원인(또는 풀스캔이 정상인 이유)으로 분명히 지목했다.
- candidate: 기대 원인을 가능성·후보로만 언급하고 정보가 없어 확정할 수 없다고 했다.
- missed: 기대 원인을 지목하지 않았거나 다른 원인으로 결론 냈다.

반드시 JSON 한 줄만 출력한다: {{"label": "confirmed|candidate|missed", "reason": "한 문장"}}

--- 응답 시작 ---
{response}
--- 응답 끝 ---"""


def human_labels():
    labels = {}
    text = (EXP / "ai-masking-human-review.md").read_text()
    for line in text.splitlines():
        m = re.match(r"\| (L\d|C\d) \| \w+ \| [^|]+ \| ([^|]+) \| ([^|]+) \| ([^|]+) \|", line)
        if not m or m.group(1) in EXCLUDED:
            continue
        for cond, cell in zip("ABC", m.groups()[1:]):
            labels[(m.group(1), cond)] = MARK[cell.strip()]
    return labels


def judge_one(case_id, response):
    prompt = RUBRIC.format(cause=CASES[case_id]["expectedCause"], response=response)
    out = subprocess.run(["claude", "-p", "--output-format", "json", "--setting-sources", ""],
                         input=prompt, capture_output=True, text=True, timeout=300)
    data = json.loads(out.stdout)
    model = ",".join(sorted((data.get("modelUsage") or {}).keys()))
    m = re.search(r"\{.*\}", data.get("result", ""), re.S)
    verdict = json.loads(m.group(0)) if m else {"label": "unparsed", "reason": data.get("result", "")[:200]}
    return verdict["label"], verdict.get("reason", ""), model


def run(items, out_path):
    done = set()
    if out_path.exists():
        for line in out_path.read_text().splitlines():
            if line.strip():
                n = json.loads(line)
                done.add((n["caseId"], n["condition"], n["rep"]))
    todo = [it for it in items if (it["caseId"], it["condition"], it["rep"]) not in done]
    with cf.ThreadPoolExecutor(4) as pool, out_path.open("a") as f:
        futures = {pool.submit(judge_one, it["caseId"], it["response"]): it for it in todo}
        for i, fut in enumerate(cf.as_completed(futures), 1):
            it = futures[fut]
            label, reason, model = fut.result()
            f.write(json.dumps({"caseId": it["caseId"], "condition": it["condition"], "rep": it["rep"],
                                "label": label, "reason": reason, "judgeModel": model}, ensure_ascii=False) + "\n")
            f.flush()
            print(f"{i}/{len(todo)} {it['caseId']}/{it['condition']}/{it['rep']} {label}", flush=True)


def first_run():
    items = []
    for line in (EXP / "ai-masking-responses.jsonl").read_text().splitlines():
        n = json.loads(line)
        if n["caseId"] not in EXCLUDED:
            items.append({"caseId": n["caseId"], "condition": n["condition"], "rep": 1, "response": n["response"]})
    return items


def main(mode):
    if mode == "calibrate":
        out = EXP / "ai-masking-judge-calibration.jsonl"
        run(first_run(), out)
        human = human_labels()
        rows = [json.loads(l) for l in out.read_text().splitlines() if l.strip()]
        agree = [r for r in rows if human.get((r["caseId"], r["condition"])) == r["label"]]
        print(f"사람 판정과 일치 {len(agree)}/{len(rows)}")
        for r in rows:
            h = human.get((r["caseId"], r["condition"]))
            if h != r["label"]:
                print("불일치", r["caseId"], r["condition"], "사람", h, "판정기", r["label"], r["reason"])
    elif mode == "judge":
        items = [it for it in first_run() if it["condition"] in ("B", "C")]
        for line in (EXP / "ai-masking-repeat.jsonl").read_text().splitlines():
            n = json.loads(line)
            if n.get("error") is None:
                items.append({"caseId": n["caseId"], "condition": n["condition"], "rep": n["rep"], "response": n["response"]})
        run(items, EXP / "ai-masking-judged.jsonl")


if __name__ == "__main__":
    main(sys.argv[1])
