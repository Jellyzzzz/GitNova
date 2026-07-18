package com.gitnova.service.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gitnova.entity.ReviewComment;
import com.gitnova.mapper.ReviewCommentMapper;
import com.gitnova.service.agent.AgentTool;
import com.gitnova.websocket.ReviewPushHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 工具 4 — 提交最终 Code Review 结果（写操作）
 *
 * Agent 调用此工具表示审查完成，结果写入 review_comment 表并通过 WebSocket 推送。
 * Agent Loop 检测到 submitReview 调用时终止循环。
 */
@Component
public class SubmitReviewTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(SubmitReviewTool.class);

    private final ReviewCommentMapper reviewCommentMapper;
    private final ReviewPushHandler reviewPushHandler;
    private final ObjectMapper objectMapper;

    public SubmitReviewTool(ReviewCommentMapper reviewCommentMapper,
                            ReviewPushHandler reviewPushHandler,
                            ObjectMapper objectMapper) {
        this.reviewCommentMapper = reviewCommentMapper;
        this.reviewPushHandler = reviewPushHandler;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "submitReview";
    }

    @Override
    public String description() {
        return "提交最终的 Code Review 结果。参数: repoId(仓库ID), commitSha1(commit的SHA-1), reviewJson(JSON数组格式的review结果)";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("commitSha1", Map.of("type", "string", "description", "commit的SHA-1"));
        props.put("reviewJson", Map.of("type", "string", "description", "JSON数组格式的review结果"));
        schema.put("properties", props);
        schema.put("required", List.of("commitSha1", "reviewJson"));  // repoKey/repoId 由 Loop 注入
        return schema;
    }

    /** 写操作，必须独占 */
    @Override
    public boolean isConcurrencySafe() {
        return false;
    }

    @Override
    @SuppressWarnings("unchecked")
    public String execute(Map<String, String> params) {
        // TODO: Phase 4 — 解析 reviewJson，写入 review_comment 表，通过 WebSocket 推送
        throw new UnsupportedOperationException("Phase 4: 待实现");
    }
}
