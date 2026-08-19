package com.gitnova.service.agent.review;

import com.gitnova.service.agent.runtime.AgentRunContext;
import com.gitnova.service.agent.runtime.ReviewCoverage;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Validates that a final draft is grounded in trusted review coverage. */
public class ReviewVerifier {

    public ReviewVerification verify(
            AgentRunContext context,
            ReviewDraft draft,
            ReviewCoverage coverage
    ) {
        Objects.requireNonNull(context,"context must not be null");
        Objects.requireNonNull(draft,"draft must not be null");
        Objects.requireNonNull(coverage,"coverage must not be null");

        List<String> feedback=new ArrayList<>();
        if(draft.summary()==null||draft.summary().isBlank()){
            feedback.add("summary must not be blank");
        }
        if(draft.issues()==null){
            feedback.add("issues must not be null");
            return ReviewVerification.rejected(true,feedback);
        }
        if(!coverage.changesListed()){
            feedback.add("listChanges must succeed before finalizing the review");
        }
        for(ReviewIssueDraft issue:draft.issues()){
            if(issue==null){
                feedback.add("issues must not contain null entries");
                continue;
            }
            boolean inspected=coverage.diffedFiles().contains(issue.filePath())
                    ||coverage.readFiles().contains(issue.filePath());
            if(!inspected){
                feedback.add("issue file was not inspected: "+issue.filePath());
            }
        }
        return feedback.isEmpty()
                ?ReviewVerification.accept()
                :ReviewVerification.rejected(true,feedback);
    }
}
