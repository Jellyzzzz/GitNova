package com.gitnova.service.agent.runtime;

import com.gitnova.service.agent.model.ModelUsage;
import com.gitnova.service.agent.review.ReviewDraft;

import java.util.List;
import java.util.Objects;

public record AgentRunResult(
        AgentRunStatus status,
        AgentTerminationReason terminationReason,
        ReviewDraft reviewDraft,
        int modelCallCount,
        int toolCallCount,
        List<ModelUsage> modelUsages
        ) {
    public AgentRunResult{
        Objects.requireNonNull(status,"status must not be null");
        Objects.requireNonNull(terminationReason,"terminationReason must not be null");
        Objects.requireNonNull(modelUsages,"modelUsages must not be null");
        if(status==AgentRunStatus.COMPLETED) {
            Objects.requireNonNull(reviewDraft,"completed run must contain reviewDraft");
            if(terminationReason!=AgentTerminationReason.FINALIZE_SUCCEEDED){
                throw new IllegalArgumentException("completed run must end with FINALIZE_SUCCEEDED");
            }
        }else {
            if(reviewDraft!=null) throw new IllegalArgumentException("non-completed run must not contain reviewDraft");
            if(terminationReason==AgentTerminationReason.FINALIZE_SUCCEEDED) throw new IllegalArgumentException("only completed run can finalize successfully");
        }
        if(modelCallCount<0||toolCallCount<0){
            throw new IllegalArgumentException("call counts must not be negative");
        }
        modelUsages=List.copyOf(modelUsages);
    }

}
