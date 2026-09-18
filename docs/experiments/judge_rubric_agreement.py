"""기준표를 바꿨을 때 판정 일치율이 오르는지 잰다 — 판정자 고정, 기준표만 변수(#137).

설계는 3 x 2 다. 로컬 모델 셋(qwen3:14b, llama3.1:8b, qwen3:8b)에게 같은 응답 162건을
옛 기준표(v1)와 새 기준표(v2)로 각각 판정시킨다. 판정자가 고정이므로 일치율 차이는 기준표 차이다.

집계 대상은 문서의 진단력 표에 있는 126칸이다(뺀 사례 넷은 제외). #131 표와 같은 분모라야 비교가 된다.

사용법: python3 judge_rubric_agreement.py [ai-masking-judge-rubric-ab.jsonl]
"""
import collections
import itertools
import json
import sys
from pathlib import Path

MODELS = ["qwen3:14b", "llama3.1:8b", "qwen3:8b"]
CONDITIONS = ("NONE", "STRUCTURE", "FULL")
# #131 에서 집계한 14개 사례 — 뺀 사례 넷(M4·P2·P7·C3)을 빼고 남은 것
CASES = ["M1", "M2", "M3", "M5", "M6", "M7", "M8", "P1", "P3", "P4", "P5", "P6", "C1", "C2"]
KEYS = [(case, cond, rep) for case in CASES for cond in CONDITIONS for rep in (1, 2, 3)]


def load_all(path):
    """판정 한 파일에 모델·기준표가 섞여 있다 — (기준표, 모델)로 갈라 담는다."""
    out = {}
    for line in Path(path).read_text().splitlines():
        if line.strip():
            r = json.loads(line)
            out.setdefault(r["rubric"], {}).setdefault(r["judgeModel"], {})[
                (r["caseId"], r["condition"], r["rep"])] = r["label"]
    return out


def report(version, judges):
    print(f"\n=== 기준표 {version} ===")
    missing = [n for n, j in judges.items() if any(k not in j for k in KEYS)]
    if missing:
        print(f"  (판정이 모자란 판정자: {missing})")
        return None

    pairs = []
    for (na, a), (nb, b) in itertools.combinations(judges.items(), 2):
        n = sum(1 for k in KEYS if a[k] == b[k])
        pairs.append(n / len(KEYS))
        print(f"  {na} - {nb}: {n}/{len(KEYS)} ({n / len(KEYS) * 100:.0f}%)")

    same = sum(1 for k in KEYS if len({j[k] for j in judges.values()}) == 1)
    print(f"  셋 다 같음: {same}/{len(KEYS)} ({same / len(KEYS) * 100:.0f}%)")

    print("  판정자별 확정 비율 (NONE | STRUCTURE | FULL)")
    for name, j in judges.items():
        row = []
        for cond in CONDITIONS:
            cells = [k for k in KEYS if k[1] == cond]
            c = sum(1 for k in cells if j[k] == "confirmed")
            row.append(f"{c}/{len(cells)} ({c / len(cells) * 100:.0f}%)")
        print(f"    {name}: {' | '.join(row)}")

    print("  갈림의 성격(다른 칸만)")
    for (na, a), (nb, b) in itertools.combinations(judges.items(), 2):
        c = collections.Counter(tuple(sorted((a[k], b[k]))) for k in KEYS if a[k] != b[k])
        print(f"    {na} - {nb}: {dict(c)}")

    unparsed = sum(1 for j in judges.values() for k in KEYS if j[k] == "unparsed")
    print(f"  파싱 실패: {unparsed}")
    return {"same": same, "total": len(KEYS), "pairs": pairs}


def main():
    path = Path(sys.argv[1] if len(sys.argv) > 1 else "docs/experiments/ai-masking-judge-rubric-ab.jsonl")
    everything = load_all(path)
    results = {}
    for version in ("v1", "v2"):
        judges = {m: everything.get(version, {}).get(m) for m in MODELS
                  if everything.get(version, {}).get(m)}
        if len(judges) == len(MODELS):
            results[version] = report(version, judges)
        else:
            print(f"\n=== 기준표 {version} === (판정 부족: {sorted(set(MODELS) - set(judges))})")

    if len(results) == 2 and all(results.values()):
        before, after = results["v1"], results["v2"]
        print("\n=== 기준표를 바꿔 달라진 것 ===")
        print(f"  셋 다 같음: {before['same']}/{before['total']} ({before['same'] / before['total'] * 100:.0f}%)"
              f" -> {after['same']}/{after['total']} ({after['same'] / after['total'] * 100:.0f}%)")
        bp = sum(before["pairs"]) / len(before["pairs"]) * 100
        ap = sum(after["pairs"]) / len(after["pairs"]) * 100
        print(f"  짝 평균 일치: {bp:.0f}% -> {ap:.0f}%")
        passed = after["same"] / after["total"] > 0.80
        print(f"  #131 기준(셋 다 같음 80% 초과): {'넘었다' if passed else '못 넘었다'}")


if __name__ == "__main__":
    main()
