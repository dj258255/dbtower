"""#159 — 사람이 정한 정답과 판정자 셋의 일치율을 낸다.

사용법:
1. `masking-human-review-queue.md` 의 각 칸에서 `정답(사람): ` 뒤에
   confirmed / candidate / missed 중 하나를 적는다.
2. `python3 judge_human_agreement.py`

사람이 정한 값만 정답으로 삼는다. 판정자 셋(qwen3:14b, llama3.1:8b, qwen3:8b)이
그 정답과 얼마나 맞는지, 다수결이 맞는지를 낸다. 판정자를 늘리는 것으로는 일치율이
오르지 않는다는 것이 #131·#137 에서 확인됐으므로, 남은 30% 를 사람이 닫는 단계다.
"""
import collections
import json
import re
import sys
from pathlib import Path

MODELS = ["qwen3:14b", "llama3.1:8b", "qwen3:8b"]
LABELS = {"confirmed", "candidate", "missed"}
QUEUE = Path("docs/experiments/masking-human-review-queue.md")
JUDGE = Path("docs/experiments/ai-masking-judge-rubric-ab.jsonl")


def parse_human(path):
    """`## 1. P3 / FULL / rep 1` 블록에서 `정답(사람): X` 를 읽는다."""
    out = {}
    text = path.read_text(encoding="utf-8")
    for block in re.split(r"\n## ", text)[1:]:
        m = re.match(r"\d+\.\s+(\S+)\s*/\s*(\S+)\s*/\s*rep\s+(\d+)", block)
        if not m:
            continue
        key = (m.group(1), m.group(2), int(m.group(3)))
        h = re.search(r"정답\(사람\):\s*(\S+)", block)
        if h and h.group(1) in LABELS:
            out[key] = h.group(1)
    return out


def main():
    queue = Path(sys.argv[1]) if len(sys.argv) > 1 else QUEUE
    human = parse_human(queue)
    if not human:
        print("아직 사람 정답이 없다 — " + str(queue) + " 의 `정답(사람):` 뒤를 채워라")
        return 1

    judges = {}
    for line in JUDGE.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        r = json.loads(line)
        if r["rubric"] != "v1":
            continue
        judges.setdefault(r["judgeModel"], {})[(r["caseId"], r["condition"], r["rep"])] = r["label"]

    n = len(human)
    print(f"사람 정답 {n}칸\n")
    for m in MODELS:
        j = judges.get(m, {})
        ok = sum(1 for k, v in human.items() if j.get(k) == v)
        print(f"  {m:14s}: {ok}/{n} ({ok / n * 100:.0f}%)")

    agree = 0
    counts = collections.Counter()
    for k, v in human.items():
        votes = [judges[m][k] for m in MODELS if k in judges.get(m, {})]
        if not votes:
            continue
        top = max(set(votes), key=votes.count)
        counts[(v, top)] += 1
        if top == v:
            agree += 1
    print(f"  {'다수결':14s}: {agree}/{n} ({agree / n * 100:.0f}%)")
    print("\n  (사람 정답, 다수결) 분포:", dict(counts))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
