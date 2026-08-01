package com.gitnova.service.agent.review;

import java.util.List;

public record ReviewDraft(String summary, List<ReviewIssueDraft>issues) {
}
