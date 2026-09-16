package io.dbtower.aiops;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * AI 결과와 플랫폼 사실을 분리해 보여주는 결과 계약.
 *
 * <p>facts·ruleFindings는 DBTower가 수집 시점에 직접 만든 값이다 — 실행기가 보낸 값으로 덮어쓰지 않는다.
 * aiOpinion·evidence·nextActions만 모델이 만들고, 그 안의 수치와 인용이 사실 목록에 없으면
 * unverifiedClaims에 남긴다. 화면·Slack은 이 목록이 비어 있지 않으면 경고와 함께 보여준다.</p>
 *
 * @param facts            DBTower가 수집한 사실(F1, F2 ... 순서)
 * @param ruleFindings     결정론적 규칙 판정(G1, G2 ...)
 * @param aiOpinion        AI 1차 소견. AI가 꺼져 있거나 형식이 틀리면 null
 * @param references       검색된 과거 사례·런북(R1, R2 ...). 판단 근거가 아니라 참고 자료다
 * @param unverifiedClaims 사실 목록과 대조되지 않은 수치·인용
 * @param backend          모델 호출 백엔드(api/cli/off)
 * @param promptVersion    프롬프트 틀과 판단 기준 문서의 버전
 */
public record AiOperationResult(
        List<String> facts,
        List<String> ruleFindings,
        String aiOpinion,
        List<String> evidence,
        List<String> uncertainties,
        List<String> nextActions,
        List<String> references,
        List<String> unverifiedClaims,
        boolean approvalRequired,
        String backend,
        String promptVersion,
        OffsetDateTime completedAt) {

    public AiOperationResult {
        facts = copy(facts);
        ruleFindings = copy(ruleFindings);
        evidence = copy(evidence);
        uncertainties = copy(uncertainties);
        nextActions = copy(nextActions);
        references = copy(references);
        unverifiedClaims = copy(unverifiedClaims);
    }

    private static List<String> copy(List<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
