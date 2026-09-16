package io.dbtower.aiops.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dbtower.aiops.AiOperationJobView;
import io.dbtower.aiops.AiOperationResult;
import io.dbtower.aiops.internal.domain.AiOperationJob;
import io.dbtower.aiops.internal.domain.AiOperationResultEntity;
import org.springframework.stereotype.Component;

import java.util.List;

/** 엔티티와 공개 뷰 사이의 변환, 목록 값의 JSON 직렬화를 한 곳에 둔다. */
@Component
public class JobViews {

    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {
    };

    private final ObjectMapper mapper = new ObjectMapper();

    public AiOperationJobView view(AiOperationJob job, AiOperationResultEntity result) {
        return new AiOperationJobView(job.getJobId(), job.getRequestId(), job.getType(), job.getStatus(),
                job.getTrigger(), job.getRequester(), job.getSubmittedBy(), job.getScopeTeam(), job.getInstanceId(),
                job.getInstanceType(),
                job.getWindowFrom(), job.getWindowTo(), job.getPrompt(), job.getAttempt(), job.getReplyChannel(),
                job.getReplyThread(), job.getRequestedAt(), job.getUpdatedAt(), job.getNotifiedAt(),
                job.getFailureReason(), result == null ? null : result(result));
    }

    public AiOperationResult result(AiOperationResultEntity r) {
        return new AiOperationResult(read(r.getFacts()), read(r.getRuleFindings()), r.getAiOpinion(),
                read(r.getEvidence()), read(r.getUncertainties()), read(r.getNextActions()), read(r.getReferences()),
                read(r.getUnverifiedClaims()), r.isApprovalRequired(), r.getBackend(), r.getPromptVersion(),
                r.getCompletedAt());
    }

    public String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("AI 운영 작업 값을 직렬화하지 못했습니다", e);
        }
    }

    public List<String> read(String json) {
        try {
            return json == null ? List.of() : mapper.readValue(json, STRINGS);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("저장된 AI 운영 결과를 읽지 못했습니다", e);
        }
    }
}
