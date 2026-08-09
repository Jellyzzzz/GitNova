package com.gitnova.event;

import org.springframework.context.ApplicationEvent;

import java.util.Objects;

/**
 * Push 成功后发布的事件
 *
 * 💡 Design Note：Spring Event 解耦 push 主流程与 Agent 处理。
 * push 主流程完全不知道 Agent 的存在，未来加新的 Hook
 * （比如 CI 触发、Webhook 通知）只需新增 Listener，不用改 TransferService 一行代码。
 */
public class PostReceiveEvent extends ApplicationEvent {

    private final Long repoId;
    private final String baseSha1;
    private final String targetSha1;
    private final Long pusherId;
    private final boolean requestReview;

    public PostReceiveEvent(
            Object source,
            Long repoId,
            String baseSha1,
            String targetSha1,
            Long pusherId,
            boolean requestReview
    ) {
        super(source);
        this.repoId = Objects.requireNonNull(repoId, "repoId must not be null");
        this.baseSha1 = baseSha1;
        this.targetSha1 = Objects.requireNonNull(
                targetSha1,
                "targetSha1 must not be null"
        );
        this.pusherId = pusherId;
        this.requestReview = requestReview;
    }

    public Long getRepoId() {
        return repoId;
    }

    public String getBaseSha1() {
        return baseSha1;
    }

    public String getTargetSha1() {
        return targetSha1;
    }

    public Long getPusherId() {
        return pusherId;
    }

    public boolean isRequestReview() {
        return requestReview;
    }
}
