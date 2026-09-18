"""기준표를 바꿔 다시 판정한다 — 판정자는 고정하고 기준표만 바꾼다(#137).

#131에서 판정자를 셋으로 늘렸는데 일치가 51%에 그쳤다. 갈린 칸을 분해하니 확정과 놓침으로
정반대인 칸은 126칸 중 하나뿐이고 나머지는 거의 전부 확정과 후보 사이였다. 병목이 판정자 수가 아니라
기준표의 "분명히 지목했다"를 모델마다 다르게 읽는 데 있다는 뜻이다.

그래서 이번에는 판정자를 늘리지 않고 **기준표만** 바꾼다. 같은 모델 셋에게 옛 기준표와 새 기준표를
각각 주고 일치율이 오르는지 본다. 오르면 기준표가 병목이었다는 것이 측정으로 선다.

사용법: python3 judge_v2.py <levels.jsonl> <cases.json> <출력.jsonl> <모델> <v1|v2>
"""
import json
import re
import sys
import urllib.request
import time
from pathlib import Path

# 옛 기준표 — #100·#131에서 쓴 것. 비교 기준으로 그대로 둔다
RUBRIC_V1 = """너는 SQL 성능 진단 응답을 채점하는 채점자다. 응답을 고치거나 새로 진단하지 말고 라벨만 정한다.

기대 원인: {cause}

라벨 정의:
- confirmed: 응답이 기대 원인을 이 쿼리의 원인(또는 풀스캔이 정상인 이유)으로 분명히 지목했다.
- candidate: 기대 원인을 가능성·후보로만 언급하고 정보가 없어 확정할 수 없다고 했다.
- missed: 기대 원인을 지목하지 않았거나 다른 원인으로 결론 냈다.

반드시 JSON 한 줄만 출력한다: {{"label": "confirmed|candidate|missed"}}

--- 응답 시작 ---
{response}
--- 응답 끝 ---"""

# 새 기준표 — '분명히'를 응답에서 눈으로 확인할 수 있는 조건 둘로 바꾼다
RUBRIC_V2 = """너는 SQL 성능 진단 응답을 채점하는 채점자다. 응답을 고치거나 새로 진단하지 말고 라벨만 정한다.

기대 원인: {cause}

아래 세 물음을 순서대로 답해 라벨을 정한다. 앞 물음에서 결정되면 뒤는 보지 않는다.

물음 1. 응답이 기대 원인을 원인으로 들었는가?
  들지 않았거나 다른 것을 원인으로 결론 냈다 -> missed

물음 2. 기대 원인을 말하는 문장이나 결론 문장에 불확실 표현이 붙어 있는가?
  불확실 표현의 예: "확인할 수 없다", "알 수 없다", "정보가 없다", "추정된다", "가능성이 있다",
  "~일 수 있다", "~로 보인다", "단정할 수 없다", "다만", "추가 확인이 필요하다"
  붙어 있다 -> candidate
  (원인을 하나로 좁혔더라도 마찬가지다. "가장 유력한 원인은 X입니다. 다만 확인할 수 없습니다"는
   원인을 하나로 좁혔지만 불확실 표현이 붙었으므로 candidate 다.)

물음 3. 원인 후보를 여럿 나열하고 기대 원인이 그중 하나로만 들어 있는가?
  그렇다 -> candidate
  아니다(기대 원인 하나로 결론 냈다) -> confirmed

정리하면 confirmed 는 둘을 모두 만족할 때만이다.
  (가) 기대 원인 하나로 결론 냈다
  (나) 그 결론 문장에 불확실 표현이 없다

반드시 JSON 한 줄만 출력한다: {{"label": "confirmed|candidate|missed"}}

--- 응답 시작 ---
{response}
--- 응답 끝 ---"""

RUBRICS = {"v1": RUBRIC_V1, "v2": RUBRIC_V2}


# ollama CLI 대신 HTTP API 를 쓴다. CLI 는 호출마다 프로세스를 새로 띄우고 qwen3 는 사고 과정을 돌려
# 건당 50초가 걸렸다. API 로 think=false 와 keep_alive 를 주면 0.5초다(90배). 판정은 라벨 하나라
# 사고 과정이 필요 없고, 세 모델에 같은 조건을 주려면 사고 과정 유무도 같아야 한다.
def judge(model, rubric, cause, response):
    prompt = rubric.format(cause=cause, response=response[:4000])
    body = json.dumps({"model": model, "prompt": prompt, "stream": False, "think": False,
                       "keep_alive": "30m", "options": {"num_predict": 64, "temperature": 0}})
    req = urllib.request.Request("http://127.0.0.1:11434/api/generate",
                                 data=body.encode(), headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=300) as r:
        text = json.loads(r.read()).get("response", "").strip()
    m = re.search(r'\{[^{}]*"label"[^{}]*\}', text, re.S)
    if not m:
        return "unparsed", text[:200]
    try:
        return json.loads(m.group(0)).get("label", "unparsed"), ""
    except json.JSONDecodeError:
        return "unparsed", text[:200]


def main():
    levels, cases_path, out_path = Path(sys.argv[1]), Path(sys.argv[2]), Path(sys.argv[3])
    model, version = sys.argv[4], sys.argv[5]
    rubric = RUBRICS[version]
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
    print(f"판정 대상 {len(todo)}건 (모델 {model}, 기준표 {version})", flush=True)

    with out_path.open("a") as f:
        for i, r in enumerate(todo, 1):
            t0 = time.time()
            label, note = judge(model, rubric, cases[r["caseId"]]["expectedCause"], r["response"])
            f.write(json.dumps({"caseId": r["caseId"], "condition": r["condition"], "rep": r["rep"],
                                "label": label, "judgeModel": model, "rubric": version, "note": note},
                               ensure_ascii=False) + "\n")
            f.flush()
            if i % 20 == 0 or i == len(todo):
                print(f"{i}/{len(todo)} {model}/{version} 마지막 {label} ({time.time() - t0:.1f}s)", flush=True)


if __name__ == "__main__":
    main()
