package com.gitnova.service.agent.review;

import com.gitnova.service.agent.context.Severity;

public record ReviewIssueDraft(
        String filePath,
        int startLine,
        int endLine,
        Severity severity,
        String category,
        String evidence,
        String explanation,
        String suggestion,
        double confidence
) {
}
