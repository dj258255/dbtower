"""과거 사례·런북 검색 — pgvector 벡터 유사도 + 문자 2-gram 어휘 점수의 하이브리드.

플랫폼 메타 DB가 아니라 별도 pgvector 인스턴스에 둔다. 이 저장소는 문서에서 다시 만들 수 있는 파생 데이터라서,
플랫폼 DB에 확장(vector)과 임베딩 모델 결합을 들이지 않았다.

순위를 벡터만으로 매기지 않는 이유는 실측이다(VERIFICATION 169절): 한국어 운영 문장 10개로 잰 1순위 정확도가
multilingual-e5-large 벡터만 7/10, 문자 2-gram만 7/10, 둘을 섞으면 9/10이었다. 문항이 적고 가중치도 같은 문항으로
골라 과적합 여지가 있다 — 그래서 1차 거름은 점수가 아니라 팀 범위와 작업 유형 필터가 한다.
"""
from __future__ import annotations

import hashlib
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Protocol, Sequence

VECTOR_CANDIDATES = 30
VECTOR_WEIGHT = 0.5


class Embedder(Protocol):
    name: str
    dim: int

    def documents(self, texts: Sequence[str]) -> list[list[float]]: ...

    def query(self, text: str) -> list[float]: ...


class FastEmbedder:
    """로컬 ONNX 임베딩(fastembed). 외부 API로 운영 문장을 보내지 않는다."""

    def __init__(self, model: str):
        from fastembed import TextEmbedding  # 무거운 의존성이라 실제로 쓸 때만 불러온다

        self.name = model
        self._model = TextEmbedding(model)
        # e5 계열은 질의와 문서에 접두어를 붙여 학습됐다 — 빼면 같은 모델도 순위가 흔들린다
        self._e5 = "e5" in model.lower()
        self.dim = len(next(iter(self._model.embed(["dim"]))))

    def documents(self, texts: Sequence[str]) -> list[list[float]]:
        prefix = "passage: " if self._e5 else ""
        return [list(map(float, v)) for v in self._model.embed([prefix + t for t in texts])]

    def query(self, text: str) -> list[float]:
        prefix = "query: " if self._e5 else ""
        return list(map(float, next(iter(self._model.embed([prefix + text])))))


@dataclass(frozen=True)
class Document:
    doc_id: str
    source: str
    title: str
    content: str
    team: str | None = None
    job_types: tuple[str, ...] | None = None
    dbms: str | None = None


@dataclass(frozen=True)
class Hit:
    doc_id: str
    source: str
    title: str
    content: str
    similarity: float
    lexical: float
    score: float

    def as_reference(self, snippet_cap: int = 600) -> dict:
        return {"id": self.doc_id, "source": self.source, "title": self.title,
                "snippet": self.content[:snippet_cap], "score": round(self.score, 3)}


def bigrams(text: str) -> set[str]:
    compact = re.sub(r"\s+", "", (text or "").lower())
    return {compact[i:i + 2] for i in range(len(compact) - 1)}


def lexical_overlap(query: str, content: str) -> float:
    q = bigrams(query)
    return len(q & bigrams(content)) / len(q) if q else 0.0


def _zscores(values: list[float]) -> list[float]:
    if len(values) < 2:
        return [0.0 for _ in values]
    mean = sum(values) / len(values)
    std = (sum((v - mean) ** 2 for v in values) / len(values)) ** 0.5
    return [0.0 if std == 0 else (v - mean) / std for v in values]


def rank(query: str, candidates: list[tuple[str, str, str, str, float]], limit: int) -> list[Hit]:
    """(doc_id, source, title, content, similarity) 후보를 하이브리드 점수로 다시 매긴다.

    후보 안에서의 표준점수로 섞는다 — 코사인 유사도(0.7~0.9에 몰림)와 2-gram 비율(0~0.5)은 눈금이 달라
    그냥 더하면 한쪽이 순위를 독점한다.
    """
    lexical = [lexical_overlap(query, c[3]) for c in candidates]
    zv = _zscores([c[4] for c in candidates])
    zl = _zscores(lexical)
    hits = [Hit(c[0], c[1], c[2], c[3], c[4], lx, VECTOR_WEIGHT * a + (1 - VECTOR_WEIGHT) * b)
            for c, lx, a, b in zip(candidates, lexical, zv, zl)]
    return sorted(hits, key=lambda h: h.score, reverse=True)[:limit]


# 절 제목의 마지막 조각이 기종 이름이면 그 기종 전용 절이다. DBTower DbmsType 이름으로 맞춘다
_DBMS_TITLES = {"mysql": "MYSQL", "postgresql": "POSTGRESQL", "sql server": "MSSQL", "mssql": "MSSQL",
                "oracle": "ORACLE", "mongodb": "MONGODB"}


def markdown_sections(path: Path, job_types: tuple[str, ...] | None = None) -> list[Document]:
    """## 제목 단위로 자른다. 런북은 새로 쓰지 않고 저장소의 운영 문서를 그대로 쓴다.

    절 제목 앞에 문서 제목(# 한 줄)을 붙인다 — 판단 기준 문서와 최소권한 문서가 둘 다 "## MySQL" 절을 가져서,
    절 제목만 두면 참고 자료 목록에서 어느 문서의 MySQL인지 구분되지 않았다.

    job_types는 사람이 문서 용도로 정한다(적재 명령의 경로:유형). 실제 적재 문서 27절로 잰 결과 점수만으로는
    "SQL Server" 절이 판단 기준 문서와 최소권한 문서 사이에서 갈리지 않았고, 관련 절과 무관한 절의 유사도가 겹쳐
    점수 하한으로도 가를 수 없었다(169절) — 그래서 거름은 점수가 아니라 용도·기종 태그가 한다.
    """
    text = path.read_text(encoding="utf-8")
    heading = re.search(r"(?m)^# (.+)$", text)
    doc_title = heading.group(1).strip() if heading else path.stem
    docs: list[Document] = []
    for index, block in enumerate(re.split(r"(?m)^## ", text)[1:], start=1):
        title, _, body = block.partition("\n")
        body = body.strip()
        if len(body) < 40:
            continue
        section = title.strip()
        dbms = _DBMS_TITLES.get(section.lower())
        docs.append(Document(f"runbook:{path.name}#{index}", "runbook", f"{doc_title} > {section}", body[:1500],
                             None, job_types, dbms))
    return docs


class KnowledgeStore:
    def __init__(self, dsn: str, embedder: Embedder, *, rebuild: bool = False):
        import psycopg
        from pgvector.psycopg import register_vector

        self._embedder = embedder
        self._conn = psycopg.connect(dsn, autocommit=True)
        self._conn.execute("CREATE EXTENSION IF NOT EXISTS vector")
        register_vector(self._conn)
        if rebuild:
            # 모델을 바꾸는 유일한 길이라 스키마 확인보다 먼저 지운다 — 확인이 먼저면 불일치로 멈춰 재적재에 닿지 못한다
            self._drop()
        self._ensure_schema()

    def close(self) -> None:
        self._conn.close()

    def _ensure_schema(self) -> None:
        dim = self._embedder.dim
        self._conn.execute(
            "CREATE TABLE IF NOT EXISTS aiops_knowledge_meta (id int PRIMARY KEY, model text NOT NULL, dim int NOT NULL)")
        row = self._conn.execute("SELECT model, dim FROM aiops_knowledge_meta WHERE id = 1").fetchone()
        if row and (row[0] != self._embedder.name or row[1] != dim):
            # 모델이 바뀌면 옛 벡터와 새 질의가 다른 공간에 있다 — 섞어서 검색하면 조용히 엉뚱한 문서가 나온다
            raise RuntimeError(f"지식 저장소가 {row[0]}({row[1]}차원)로 만들어졌습니다. "
                               f"{self._embedder.name}로 바꾸려면 ingest --rebuild로 다시 적재하세요")
        self._conn.execute(f"""
            CREATE TABLE IF NOT EXISTS aiops_knowledge (
                doc_id       text PRIMARY KEY,
                source       text NOT NULL,
                title        text NOT NULL,
                content      text NOT NULL,
                team         text,
                job_types    text[],
                content_hash text NOT NULL,
                embedding    vector({dim}) NOT NULL,
                updated_at   timestamptz NOT NULL DEFAULT now()
            )""")
        self._conn.execute("ALTER TABLE aiops_knowledge ADD COLUMN IF NOT EXISTS dbms text")
        if not row:
            self._conn.execute("INSERT INTO aiops_knowledge_meta (id, model, dim) VALUES (1, %s, %s)",
                               (self._embedder.name, dim))

    def _drop(self) -> None:
        self._conn.execute("DROP TABLE IF EXISTS aiops_knowledge")
        self._conn.execute("DROP TABLE IF EXISTS aiops_knowledge_meta")

    def upsert(self, documents: Sequence[Document]) -> int:
        """내용이 바뀐 문서만 다시 임베딩한다. 반환값은 새로 쓴 문서 수."""
        import numpy as np

        existing = dict(self._conn.execute("SELECT doc_id, content_hash FROM aiops_knowledge").fetchall())
        changed = [d for d in documents if existing.get(d.doc_id) != _hash(d)]
        if not changed:
            return 0
        vectors = self._embedder.documents([f"{d.title}\n{d.content}" for d in changed])
        with self._conn.cursor() as cur:
            for d, v in zip(changed, vectors):
                cur.execute("""
                    INSERT INTO aiops_knowledge (doc_id, source, title, content, team, job_types, dbms, content_hash,
                                                 embedding)
                    VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s)
                    ON CONFLICT (doc_id) DO UPDATE SET source = EXCLUDED.source, title = EXCLUDED.title,
                        content = EXCLUDED.content, team = EXCLUDED.team, job_types = EXCLUDED.job_types,
                        dbms = EXCLUDED.dbms, content_hash = EXCLUDED.content_hash, embedding = EXCLUDED.embedding,
                        updated_at = now()
                    """, (d.doc_id, d.source, d.title, d.content, d.team,
                          list(d.job_types) if d.job_types else None, d.dbms, _hash(d), np.array(v)))
        return len(changed)

    def search(self, query: str, team: str | None, job_type: str, limit: int = 3, dbms: str | None = None) -> list[Hit]:
        import numpy as np

        vector = np.array(self._embedder.query(query))
        # 팀 범위·유형·기종은 점수가 아니라 조건이다 — 다른 팀 사례나 다른 기종 절은 아무리 비슷해도 후보에 들지 않는다
        rows = self._conn.execute("""
            SELECT doc_id, source, title, content, 1 - (embedding <=> %s) AS similarity
            FROM aiops_knowledge
            WHERE (team IS NULL OR team = %s) AND (job_types IS NULL OR %s = ANY(job_types))
              AND (%s::text IS NULL OR dbms IS NULL OR dbms = %s)
            ORDER BY embedding <=> %s
            LIMIT %s""", (vector, team, job_type, dbms, dbms, vector, VECTOR_CANDIDATES)).fetchall()
        return rank(query, [tuple(r) for r in rows], limit)

    def count(self) -> int:
        return self._conn.execute("SELECT count(*) FROM aiops_knowledge").fetchone()[0]


def _hash(d: Document) -> str:
    raw = "\x1f".join([d.source, d.title, d.content, d.team or "", ",".join(d.job_types or ()), d.dbms or ""])
    return hashlib.sha256(raw.encode()).hexdigest()
