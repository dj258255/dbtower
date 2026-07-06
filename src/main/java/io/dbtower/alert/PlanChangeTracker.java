package io.dbtower.alert;

import io.dbtower.operator.DbmsOperatorFactory;
import io.dbtower.operator.PlanShapes;
import io.dbtower.registry.DatabaseInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 실행계획 변경(plan flip) 감지 — "쿼리도 데이터도 그대로인데 갑자기 느려짐 = 옵티마이저가
 * 플랜을 갈아탐"이라는 현업 단골 장애를 잡는다 (pganalyze plan change alerts·PMM QAN 선례).
 *
 * 트리거는 회귀 감지(RegressionDetector)다 — 모든 쿼리의 계획을 매번 뜨지 않고, 레이턴시/행수
 * 회귀가 <b>이미 감지된</b> 쿼리만 계획을 뜬다(추정 explain이라 실행 부하도 없음). A9 원칙:
 * 진단이 부하 유발자가 되면 안 된다.
 *
 * 정규화 텍스트($1·?)로 계획을 얻는 길은 기종마다 전부 다르다 — 그 획득과 shape 정규화는
 * 각 Operator의 {@code planShapeForDigest}에 맡긴다(PG GENERIC_PLAN·MySQL 샘플·MSSQL Query
 * Store·Oracle plan_hash_value·Mongo profile). 여기서는 "얻은 shape가 지난번과 다른가"만 판정한다.
 *
 * 비교 대상은 계획의 <b>형태(shape)</b>다({@link PlanShapes}) — 노드 종류·인덱스·대상만 남기고
 * 비용·추정 행수는 버린다. 추정치는 통계가 조금만 변해도 흔들려 그대로 해시하면 매번 "변경"이 된다.
 */
@Component
public class PlanChangeTracker {

    private static final Logger log = LoggerFactory.getLogger(PlanChangeTracker.class);

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
     * 회귀가 감지된 쿼리의 계획 shape를 떠서 기준선과 비교한다.
     * - 계획을 못 얻음(기종 미지원/게이트/플레이스홀더): empty (지어내지 않는다)
     * - 첫 관측: 기준선으로 저장, 변경 아님(empty)
     * - 같은 shape: empty
     * - 다른 shape: 새 스냅샷 저장 + 변경 반환
     * 어떤 실패도 밖으로 던지지 않는다 — 플랜 추적 실패가 회귀 알림 자체를 막으면 안 된다.
     */
    public Optional<PlanChange> check(DatabaseInstance instance, String queryId, String queryText) {
        try {
            Optional<String> shapeOpt = operatorFactory.create(instance)
                    .planShapeForDigest(queryId, queryText);
            if (shapeOpt.isEmpty()) {
                return Optional.empty(); // 기종별로 못 얻으면 조용히 스킵 — 회귀 알림은 계속된다
            }
            String shape = shapeOpt.get();
            String hash = PlanShapes.hash(shape);

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
}
