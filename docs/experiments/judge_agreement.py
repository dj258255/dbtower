"""판정자 셋의 일치율을 잰다 — 기본값을 바꿀지 정하기 전에 측정을 먼저 믿을 수 있는지 본다(#131).

앞 두 판정은 `ai-masking-levels.md`의 진단력 표에 기호로 들어 있다(칸이 `✓ ✓ ✓ / △ △ △`처럼
갈린 곳은 앞이 이 세션, 뒤가 qwen3:14b다). 세 번째 판정은 `ai-masking-judge3.jsonl`이다.
표를 사람이 옮겨 적지 않고 여기서 파싱하는 이유는, 옮기는 사이에 숫자가 바뀌면 그걸 잡을 방법이 없어서다.

사용법: python3 judge_agreement.py [문서.md] [3차판정.jsonl]
"""
import collections
import itertools
import json
import re
import sys
from pathlib import Path

SYMBOLS = {"✓": "confirmed", "△": "candidate", "✗": "missed"}
CONDITIONS = ("NONE", "STRUCTURE", "FULL")


def parse_cell(text):
    """`✓ ✓ ✓` 또는 `✓ ✓ ✓ / △ △ △` 한 칸을 (판정자1 3회, 판정자2 3회)로."""
    parts = [p.strip() for p in text.split("/")]
    first = [SYMBOLS[c] for c in parts[0] if c in SYMBOLS]
    second = [SYMBOLS[c] for c in parts[-1] if c in SYMBOLS]
    return first, second


def parse_table(md_path):
    judge1, judge2 = {}, {}
    for line in Path(md_path).read_text().splitlines():
        m = re.match(r"\|\s*([MPC]\d)\s*\|([^|]*)\|([^|]*)\|([^|]*)\|([^|]*)\|", line)
        if not m:
            continue
        case = m.group(1)
        for condition, text in zip(CONDITIONS, m.group(3, 4, 5)):
            first, second = parse_cell(text)
            if len(first) != 3 or len(second) != 3:
                raise SystemExit(f"셀 파싱 실패 {case}/{condition}: {text!r}")
            for rep in (1, 2, 3):
                judge1[(case, condition, rep)] = first[rep - 1]
                judge2[(case, condition, rep)] = second[rep - 1]
    return judge1, judge2


def confirm_rate(judge, keys):
    out = []
    for condition in CONDITIONS:
        cells = [k for k in keys if k[1] == condition]
        n = sum(1 for k in cells if judge[k] == "confirmed")
        out.append(f"{n}/{len(cells)} ({n / len(cells) * 100:.0f}%)")
    return " | ".join(out)


def main():
    md = sys.argv[1] if len(sys.argv) > 1 else "docs/experiments/ai-masking-levels.md"
    third = sys.argv[2] if len(sys.argv) > 2 else "docs/experiments/ai-masking-judge3.jsonl"

    judge1, judge2 = parse_table(md)
    judge3 = {}
    for line in Path(third).read_text().splitlines():
        if line.strip():
            r = json.loads(line)
            judge3[(r["caseId"], r["condition"], r["rep"])] = r["label"]

    # 3차는 뺀 사례 넷까지 판정했으므로 표에 있는 칸으로만 맞춘다
    keys = sorted(judge1)
    print(f"집계 칸 {len(keys)}")

    judges = [("이 세션", judge1), ("qwen3:14b", judge2), ("llama3.1:8b", judge3)]
    for (na, a), (nb, b) in itertools.combinations(judges, 2):
        n = sum(1 for k in keys if a[k] == b[k])
        print(f"{na} - {nb}: {n}/{len(keys)} ({n / len(keys) * 100:.0f}%)")

    same = sum(1 for k in keys if judge1[k] == judge2[k] == judge3[k])
    print(f"셋 다 같음: {same}/{len(keys)} ({same / len(keys) * 100:.0f}%)")

    print("\n판정자별 확정 비율 (NONE | STRUCTURE | FULL)")
    for name, judge in judges:
        print(f"  {name}: {confirm_rate(judge, keys)}")

    print("\n다수결 확정 비율")
    for condition in CONDITIONS:
        cells = [k for k in keys if k[1] == condition]
        n = 0
        for k in cells:
            label, count = collections.Counter(j[k] for _, j in judges).most_common(1)[0]
            if count >= 2 and label == "confirmed":
                n += 1
        print(f"  {condition}: {n}/{len(cells)} ({n / len(cells) * 100:.0f}%)")

    print("\n갈림의 성격(다른 칸만)")
    for (na, a), (nb, b) in itertools.combinations(judges, 2):
        c = collections.Counter(tuple(sorted((a[k], b[k]))) for k in keys if a[k] != b[k])
        print(f"  {na} - {nb}: {dict(c)}")

    opposite = [k for k in keys if {"confirmed", "missed"} <= {j[k] for _, j in judges}]
    print(f"\n확정과 놓침으로 갈린 칸: {len(opposite)} {opposite}")


if __name__ == "__main__":
    main()
