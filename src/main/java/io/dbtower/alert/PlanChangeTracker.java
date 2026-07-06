package io.dbtower.alert;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dbtower.operator.DbmsOperatorFactory;
import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.DbmsType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Optional;

/**
 * 실행계획 변경(plan flip) 감지 — "쿼리도 데이터도 그대로인데 갑자기 느려짐 = 옵티마이저가
 * 플랜을 갈아탐"이라는 현업 단골 장애를 잡는다 (pganalyze plan change alerts·PMM QAN 선례).
 *
 * 트리거는 회귀 감지(RegressionDetector)다 — 모든 쿼리의 계획을 매번 뜨지 않고, 레이턴시/행수
 * 회귀가 <b>이미 감지된</b> 쿼리만 계획을 뜬다(추정 explain이라 실행 부하도 없음). A9 원칙:
 * 진단이 부하 유발자가 되면 안 된다.
 *
 * 정규화 텍스트의 벽 — 통계 소스의 쿼리 텍스트는 리터럴이 지워진 형태($1·?)라 그대로 explain이
 * 안 된다. 기종별 정직 구분:
 * - PostgreSQL: EXPLAIN (GENERIC_PLAN) — PG 16의 정확히 이 용도인 기능. 플레이스홀더 채로 계획 산출
 * - 그 외: 플레이스홀더가 없는 텍스트만 시도, 있으면 스킵(지어내지 않음 — ? 를 임의 값으로 채우면
 *   타입에 따라 다른 플랜이 나와 "가짜 변경"을 만든다)
 *
 * 비교 대상은 계획의 <b>형태(shape)</b>다 — 노드 종류·인덱스·대상 테이블만 남기고 비용·추정
 * 행수는 버린다. 추정치는 통계가 조금만 변해도 흔들려서, 그대로 해시하면 매번 "변경"이 된다.
 */
@Component
public class PlanChangeTracker {

    private static final Logger log = LoggerFactory.getLogger(PlanChangeTracker.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final DbmsOperatorFactory operatorFactory;
    private final PlanSnapshotRepository repository;

    public PlanChangeTracker(DbmsOperatorFactory operatorFactory, PlanSnapshotRepository repository) {
        this.operatorFactory = operatorFactory;
        this.repository = repository;
    }

    /** 감지 결과 — 이전/현재 shape를 담아 알림 문구와 화면이 그대로 쓴다 */
    public record PlanChange(String queryId, String fromShape, String toShape) {
    }

    /**
     * 회귀가 감지된 쿼리의 계획을 떠서 기준선과 비교한다.
     * - 첫 관측: 기준선으로 저장, 변경 아님(empty)
     * - 같은 shape: empty
     * - 다른 shape: 새 스냅샷 저장 + 변경 반환
     * 어떤 실패도 밖으로 던지지 않는다 — 플랜 추적 실패가 회귀 알림 자체를 막으면 안 된다.
     */
    public Optional<PlanChange> check(DatabaseInstance instance, String queryId, String queryText) {
        if (queryText == null || !queryText.trim().toLowerCase().startsWith("select")) {
            return Optional.empty(); // 계획 비교가 의미 있는 건 SELECT — DML/DDL/SET은 대상 아님
        }
        boolean hasPlaceholder = queryText.contains("?") || queryText.matches("(?s).*\\$\\d+.*");
        if (hasPlaceholder && instance.getType() != DbmsType.POSTGRESQL) {
            log.debug("플랜 추적 스킵 instance={} queryId={} — 플레이스홀더 텍스트는 PG(GENERIC_PLAN)만 지원",
                    instance.getName(), queryId);
            return Optional.empty();
        }
        try {
            String plan = operatorFactory.create(instance).explainNormalized(queryText);
            String shape = shape(instance.getType(), plan);
            String hash = sha256(shape);

            PlanSnapshot last = repository
                    .findTopByInstanceIdAndQueryIdOrderByCapturedAtDesc(instance.getId(), queryId)
                    .orElse(null);
            if (last == null) {
                repository.save(new PlanSnapshot(instance.getId(), queryId, hash, shape, LocalDateTime.now()));
                return Optional.empty(); // 첫 관측은 기준선 — 비교 대상이 생겼을 뿐 변경이 아니다
            }
            if (last.getPlanHash().equals(hash)) {
                return Optional.empty();
            }
            repository.save(new PlanSnapshot(instance.getId(), queryId, hash, shape, LocalDateTime.now()));
            log.info("플랜 변경 감지 instance={} queryId={} {} -> {}",
                    instance.getName(), queryId, last.getPlanShape(), shape);
            return Optional.of(new PlanChange(queryId, last.getPlanShape(), shape));
        } catch (Exception e) {
            log.debug("플랜 추적 실패(회귀 알림은 계속) instance={} queryId={} cause={}",
                    instance.getName(), queryId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 계획 원문 -> 정규화 shape. PG(FORMAT JSON)는 노드 종류·인덱스·대상만 뽑아 트리로 직렬화,
     * 그 외 텍스트 계획은 숫자·공백을 지워 구조만 남긴다.
     */
    String shape(DbmsType type, String plan) {
        if (type == DbmsType.POSTGRESQL) {
            try {
                JsonNode root = MAPPER.readTree(plan);
                JsonNode planNode = root.isArray() ? root.get(0).get("Plan") : root.path("Plan");
                StringBuilder sb = new StringBuilder();
                walk(planNode, sb);
                return sb.toString();
            } catch (Exception e) {
                // JSON 파싱 실패 시 텍스트 정규화로 폴백 — shape가 거칠어질 뿐 감지는 계속된다
            }
        }
        return plan.replaceAll("[0-9]+(\\.[0-9]+)?", "N").replaceAll("\\s+", " ").trim();
    }

    private void walk(JsonNode node, StringBuilder sb) {
        if (node == null || node.isMissingNode()) {
            return;
        }
        sb.append(node.path("Node Type").asText("?"));
        String index = node.path("Index Name").asText("");
        String rel = node.path("Relation Name").asText("");
        if (!index.isEmpty()) {
            sb.append("(").append(index).append(")");
        } else if (!rel.isEmpty()) {
            sb.append("(").append(rel).append(")");
        }
        JsonNode children = node.path("Plans");
        if (children.isArray() && children.size() > 0) {
            sb.append(">[");
            for (int i = 0; i < children.size(); i++) {
                if (i > 0) {
                    sb.append(",");
                }
                walk(children.get(i), sb);
            }
            sb.append("]");
        }
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
