"""하이브리드 순위 단위 검증 + 실제 pgvector로 팀·유형 필터와 모델 교체 보호를 본다(pgvector 없으면 건너뛴다)."""
import hashlib
import uuid
from pathlib import Path

import pytest

from dbtower_aiops.knowledge import Document, KnowledgeStore, lexical_overlap, markdown_sections, rank

from .conftest import knowledge_dsn


def test_눈금이_다른_두_점수를_표준점수로_섞는다():
    # 벡터 유사도는 0.80~0.82에 몰려 있다. 그냥 더하면 어휘 점수가 순위를 독점하거나 사라진다
    candidates = [("a", "runbook", "락", "락 대기가 늘면 블로킹 세션을 찾는다", 0.82),
                  ("b", "runbook", "디스크", "디스크 사용률이 늘면 큰 테이블을 본다", 0.81),
                  ("c", "runbook", "백업", "백업이 없으면 정책을 본다", 0.80)]
    top = rank("세션이 락 대기로 멈췄어요", candidates, limit=2)
    assert top[0].doc_id == "a"
    assert len(top) == 2
    assert lexical_overlap("락 대기", "락 대기가 늘면") == 1.0


def test_운영_문서를_절_단위로_자른다(tmp_path: Path):
    doc = tmp_path / "ops.md"
    doc.write_text("# 제목\n\n## 1. 첫 절\n" + "본문 " * 20 + "\n## 짧은 절\n짧다\n", encoding="utf-8")
    sections = markdown_sections(doc)
    assert [s.title for s in sections] == ["제목 > 1. 첫 절"]
    assert sections[0].doc_id == "runbook:ops.md#1"


class HashEmbedder:
    """테스트 전용 결정적 임베더 — 저장·필터 계약만 본다. 순위 품질은 fastembed 실측(169절)으로 따로 잰다."""

    def __init__(self, name="test-hash", dim=8):
        self.name, self.dim = name, dim

    def _vec(self, text):
        digest = hashlib.sha256(text.encode()).digest()
        return [b / 255 + 0.01 for b in digest[: self.dim]]

    def documents(self, texts):
        return [self._vec(t) for t in texts]

    def query(self, text):
        return self._vec(text)


@pytest.fixture
def store_dsn():
    try:
        import psycopg

        psycopg.connect(knowledge_dsn(), connect_timeout=2).close()
    except Exception:  # noqa: BLE001
        pytest.skip("테스트용 pgvector 없음(docker compose --profile aiops up -d aiops-vector)")
    return knowledge_dsn()


def fresh_store(dsn, embedder):
    return KnowledgeStore(dsn, embedder, rebuild=True)


def test_다른_팀_사례와_다른_유형_사례는_후보에도_들지_않는다(store_dsn):
    store = fresh_store(store_dsn, HashEmbedder())
    try:
        store.upsert([
            Document("runbook:x#1", "runbook", "공용 런북", "모든 팀이 보는 런북"),
            Document(f"case:{uuid.uuid4()}", "case", "A팀 백업 사례", "A팀 백업 사례", "team-a", ("BACKUP_RISK_REVIEW",)),
            Document(f"case:{uuid.uuid4()}", "case", "B팀 백업 사례", "B팀 백업 사례", "team-b", ("BACKUP_RISK_REVIEW",)),
            Document(f"case:{uuid.uuid4()}", "case", "A팀 쿼리 사례", "A팀 쿼리 사례", "team-a", ("QUERY_DIAGNOSIS",)),
        ])
        titles = {h.title for h in store.search("백업 사례", "team-a", "BACKUP_RISK_REVIEW", limit=10)}
        assert titles == {"공용 런북", "A팀 백업 사례"}
    finally:
        store.close()


def test_기종_전용_절은_다른_기종_작업의_후보가_아니다(store_dsn, tmp_path):
    doc = tmp_path / "rules.md"
    doc.write_text("# 판단 규칙\n\n## MySQL\n" + "type=ALL이면 풀스캔이다 " * 5 + "\n## PostgreSQL\n"
                   + "Seq Scan이면 풀스캔이다 " * 5 + "\n## 공통 전제\n" + "풀스캔은 신호다 " * 5 + "\n", encoding="utf-8")
    sections = markdown_sections(doc, ("QUERY_DIAGNOSIS",))
    assert [s.dbms for s in sections] == ["MYSQL", "POSTGRESQL", None]
    store = fresh_store(store_dsn, HashEmbedder())
    try:
        store.upsert(sections)
        titles = {h.title for h in store.search("풀스캔", None, "QUERY_DIAGNOSIS", limit=10, dbms="MYSQL")}
        assert titles == {"판단 규칙 > MySQL", "판단 규칙 > 공통 전제"}
        # 유형 태그 밖의 작업에는 붙지 않는다(백업 위험 검토에 실행계획 규칙을 붙이지 않는다)
        assert store.search("풀스캔", None, "BACKUP_RISK_REVIEW", limit=10, dbms="MYSQL") == []
    finally:
        store.close()


def test_바뀌지_않은_문서는_다시_임베딩하지_않는다(store_dsn):
    store = fresh_store(store_dsn, HashEmbedder())
    try:
        docs = [Document("runbook:y#1", "runbook", "제목", "본문")]
        assert store.upsert(docs) == 1
        assert store.upsert(docs) == 0
        assert store.upsert([Document("runbook:y#1", "runbook", "제목", "바뀐 본문")]) == 1
    finally:
        store.close()


def test_임베딩_모델이_바뀌면_섞어_쓰지_않고_멈추고_재적재로만_바꾼다(store_dsn):
    fresh_store(store_dsn, HashEmbedder("model-a", 8)).close()
    with pytest.raises(RuntimeError, match="ingest --rebuild"):
        KnowledgeStore(store_dsn, HashEmbedder("model-b", 16))
    # 안내한 복구 경로가 실제로 열려 있어야 한다 — 처음엔 생성자가 먼저 멈춰 재적재에 닿지 못했다
    KnowledgeStore(store_dsn, HashEmbedder("model-b", 16), rebuild=True).close()
    KnowledgeStore(store_dsn, HashEmbedder("model-b", 16)).close()
