package com.gitnova.event;

import org.springframework.context.ApplicationEvent;

/**
 * Push 成功后发布的事件
 *
 * 💡 Design Note：Spring Event 解耦 push 主流程与 Agent 处理。
 * push 主流程完全不知道 Agent 的存在，未来加新的 Hook
 * （比如 CI 触发、Webhook 通知）只需新增 Listener，不用改 TransferService 一行代码。
 */
public class PostReceiveEvent extends ApplicationEvent {

    private final Long repoId;
    private final String commitSha1;
    private final Long pusherId;

    public PostReceiveEvent(Object source, Long repoId, String commitSha1, Long pusherId) {
        super(source);
        this.repoId = repoId;
        this.commitSha1 = commitSha1;
        this.pusherId = pusherId;
    }

    public Long getRepoId() {
        return repoId;
    }

    public String getCommitSha1() {
        return commitSha1;
    }

    public Long getPusherId() {
        return pusherId;
    }
}
