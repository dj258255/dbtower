"""E5 — 복제 슬롯의 WAL 보존 상한: 수신자가 죽어 있는 동안 디스크를 지킬 것인가, 이어받기를 지킬 것인가.

일회용 PostgreSQL 16 컨테이너 두 개를 띄워 같은 절차를 돌린다. 공용 컨테이너(dbtower-postgres)는 건드리지 않는다.
  unlimited : max_slot_wal_keep_size = -1 (기본값, 무제한 보존)
  limit32   : max_slot_wal_keep_size = 32MB

공유 Docker VM 디스크를 쓰지 않도록 데이터 디렉터리와 수신 디렉터리를 tmpfs에 올리고, 끝나면 컨테이너를 볼륨째 지운다.
(첫 시도에서 익명 볼륨에 WAL이 쌓여 VM 디스크가 가득 찼다.)

절차: 수신기(pg_receivewal --slot) 시작 -> 수신 확인 -> 수신기 kill -> WAL 생성(라운드마다 pg_wal 크기와 슬롯 상태 기록)
-> 수신기 재시작 -> 이어받기 성공 여부와 받은 세그먼트 연속성 확인 -> 컨테이너 삭제.
실행: python3 docs/experiments/wal_slot_limit.py  (결과: docs/experiments/wal-slot-limit.md, .csv)
"""
import csv
import datetime as dt
import pathlib
import subprocess
import time

HERE = pathlib.Path(__file__).resolve().parent
CASES = [("unlimited", "-1"), ("limit32", "32MB")]
ROUNDS = 8
ROWS_PER_ROUND = 150_000


def sh(*args, check=True):
    r = subprocess.run(args, capture_output=True, text=True)
    if check and r.returncode != 0:
        raise RuntimeError(f"{args}: {r.stderr.strip()}")
    return r.stdout.strip()


def psql(name, sql):
    return sh("docker", "exec", name, "psql", "-U", "postgres", "-tAc", sql)


def receiver_running(name):
    r = subprocess.run(["docker", "exec", name, "pgrep", "-f", "pg_receivewal"], capture_output=True, text=True)
    return r.returncode == 0


def segments(name):
    out = sh("docker", "exec", name, "bash", "-c", "ls /tmp/walarch 2>/dev/null | grep -v '.partial' || true")
    return sorted(x for x in out.split() if len(x) == 24)


def contiguous(segs):
    nums = [int(s[8:16], 16) * 0x100 + int(s[16:24], 16) for s in segs]
    gaps = [(segs[i - 1], segs[i]) for i in range(1, len(nums)) if nums[i] != nums[i - 1] + 1]
    return gaps


def run_case(case, limit, rows):
    name = f"e5-{case}"
    sh("docker", "rm", "-f", "-v", name, check=False)
    sh("docker", "run", "-d", "--name", name, "-e", "POSTGRES_PASSWORD=e5",
       "--tmpfs", "/var/lib/postgresql/data:rw,size=700m", "--tmpfs", "/tmp:rw,size=400m", "postgres:16",
       "-c", "wal_level=replica", "-c", "max_wal_senders=4", "-c", "max_replication_slots=4",
       "-c", "wal_keep_size=0", "-c", "min_wal_size=32MB", "-c", "max_wal_size=32MB",
       "-c", "checkpoint_timeout=30s", "-c", f"max_slot_wal_keep_size={limit}")
    # 공식 이미지는 초기화용 임시 서버를 띄웠다가 재시작한다. 그 뒤에 붙어야 작업이 깨지지 않는다
    for _ in range(120):
        logs = subprocess.run(["docker", "logs", name], capture_output=True, text=True)
        ready = "init process complete" in (logs.stdout + logs.stderr)
        if ready and subprocess.run(["docker", "exec", name, "pg_isready", "-U", "postgres"], capture_output=True).returncode == 0:
            break
        time.sleep(1)
    time.sleep(2)
    version = psql(name, "SHOW server_version")
    sh("docker", "exec", name, "bash", "-c", "mkdir -p /tmp/walarch && chown postgres /tmp/walarch")
    sh("docker", "exec", "-u", "postgres", name, "pg_receivewal", "-D", "/tmp/walarch", "--slot=e5slot", "--create-slot")
    sh("docker", "exec", "-d", "-u", "postgres", name, "bash", "-c",
       "pg_receivewal -D /tmp/walarch --slot=e5slot > /tmp/recv1.log 2>&1")
    psql(name, "CREATE TABLE big (id bigint, pad text)")
    psql(name, "INSERT INTO big SELECT g, repeat('x', 60) FROM generate_series(1, 50000) g; SELECT pg_switch_wal()")
    time.sleep(3)
    before_kill = segments(name)
    sh("docker", "exec", name, "pkill", "-f", "pg_receivewal")
    time.sleep(2)

    for r in range(1, ROUNDS + 1):
        psql(name, f"INSERT INTO big SELECT g, repeat('x', 60) FROM generate_series(1, {ROWS_PER_ROUND}) g")
        psql(name, "SELECT pg_switch_wal()")
        psql(name, "CHECKPOINT")
        waldir_mb = float(psql(name, "SELECT round(sum(size)/1048576.0, 1) FROM pg_ls_waldir()"))
        slot = psql(name, "SELECT wal_status || '|' || coalesce(round(safe_wal_size/1048576.0,1)::text,'null') || '|' || active "
                          "FROM pg_replication_slots WHERE slot_name='e5slot'")
        status, safe_mb, active = slot.split("|")
        rows.append({"case": case, "limit": limit, "round": r, "waldir_mb": waldir_mb, "wal_status": status,
                     "safe_wal_mb": safe_mb, "slot_active": active})
        print(case, r, waldir_mb, status, safe_mb, flush=True)

    sh("docker", "exec", "-d", "-u", "postgres", name, "bash", "-c",
       "pg_receivewal -D /tmp/walarch --slot=e5slot > /tmp/recv2.log 2>&1")
    psql(name, "INSERT INTO big SELECT g, repeat('x', 60) FROM generate_series(1, 10000) g; SELECT pg_switch_wal()")
    time.sleep(8)
    running = receiver_running(name)
    log2 = sh("docker", "exec", name, "bash", "-c", "cat /tmp/recv2.log || true")
    after = segments(name)
    gaps = contiguous(after)
    final_status = psql(name, "SELECT wal_status FROM pg_replication_slots WHERE slot_name='e5slot'")
    summary = {"case": case, "limit": limit, "version": version, "segments_before_kill": len(before_kill),
               "segments_after_resume": len(after), "receiver_running_after_resume": running,
               "gaps": gaps, "final_wal_status": final_status, "resume_log": log2.strip()[-300:]}
    sh("docker", "rm", "-f", "-v", name, check=False)
    return summary


def main():
    started = dt.datetime.now().astimezone().isoformat()
    rows, summaries = [], []
    for case, limit in CASES:
        summaries.append(run_case(case, limit, rows))
    with open(HERE / "wal-slot-limit.csv", "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0].keys()))
        w.writeheader()
        w.writerows(rows)
    md = ["# E5: 복제 슬롯의 WAL 보존 상한: 디스크 대 이어받기", "",
          "이 문서와 `wal-slot-limit.csv`는 `wal_slot_limit.py`가 생성했다. 숫자를 손으로 고치지 않는다.", "",
          f"- 실행 일시: {started}",
          f"- 일회용 컨테이너 postgres:16 ({summaries[0]['version']}), max_wal_size=32MB, checkpoint_timeout=30s, wal_keep_size=0, 데이터는 tmpfs",
          f"- 수신기(pg_receivewal --slot)를 죽인 뒤 라운드마다 {ROWS_PER_ROUND:,}행 삽입 + WAL 전환 + CHECKPOINT, {ROUNDS}라운드",
          "- pg_wal 크기는 pg_ls_waldir() 합계", "",
          "## 수신기가 죽어 있는 동안", "",
          "| 라운드 | 무제한: pg_wal(MB) | 무제한: 슬롯 상태 | 상한 32MB: pg_wal(MB) | 상한 32MB: 슬롯 상태 | 상한 32MB: 남은 안전 크기(MB) |",
          "|---|---|---|---|---|---|"]
    by = {(r["case"], r["round"]): r for r in rows}
    for i in range(1, ROUNDS + 1):
        a, b = by[("unlimited", i)], by[("limit32", i)]
        md.append(f"| {i} | {a['waldir_mb']} | {a['wal_status']} | {b['waldir_mb']} | {b['wal_status']} | {b['safe_wal_mb']} |")
    md += ["", "## 수신기를 다시 붙였을 때", "",
           "| 설정 | 최종 슬롯 상태 | 재시작한 수신기가 살아 있나 | 받은 세그먼트 수 | 세그먼트 구멍 | 수신기 로그 끝 |",
           "|---|---|---|---|---|---|"]
    for s in summaries:
        log = s["resume_log"].replace("|", "/").replace("\n", " ") or "(없음)"
        md.append(f"| {s['case']} ({s['limit']}) | {s['final_wal_status']} | {s['receiver_running_after_resume']} | "
                  f"{s['segments_after_resume']} | {len(s['gaps'])}건 | {log} |")
    md += ["", "- 읽는 법: `재시작한 수신기가 살아 있나`는 프로세스 생존만 본다. 슬롯이 lost면 프로세스는 살아서 재시도만 반복한다(로그 끝 참고)",
           "- `세그먼트 구멍 0건`은 받은 세그먼트가 2개 이상일 때만 연속을 뜻한다. 1개면 비교할 쌍이 없어 0이 나온다"]
    (HERE / "wal-slot-limit.md").write_text("\n".join(md) + "\n", encoding="utf-8")
    print("E5 결과 기록:", HERE / "wal-slot-limit.md")


if __name__ == "__main__":
    main()
