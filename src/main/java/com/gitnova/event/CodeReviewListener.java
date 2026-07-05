package com.gitnova.event;

import com.gitnova.service.GitletService;
import com.gitnova.service.agent.CodeReviewAgent;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Code Review 异步监听器 — Phase 4 核心
 *
 * 监听 PostReceiveEvent，在事务提交后异步执行 Agent review，
 * 不阻塞 push 主流程。
 *
 * 💡 Design Note — 为什么用 Spring Event 而非直接 @Async 调用？
 * @Async 直接调用也能异步，但 Spring Event 的优势是解耦：
 * push 主流程完全不知道 Agent 的存在。
 */
@Component
public class CodeReviewListener {

    private final GitletService gitletService;
    private final CodeReviewAgent codeReviewAgent;

    public CodeReviewListener(GitletService gitletService,
                              CodeReviewAgent codeReviewAgent) {
        this.gitletService = gitletService;
        this.codeReviewAgent = codeReviewAgent;
    }

    /**
     * 异步处理 Code Review
     *
     * @TransactionalEventListener(phase = AFTER_COMMIT) 确保在 CAS 事务提交成功后执行
     * @Async 使用独立线程池，不阻塞 push 响应
     *
     * ⚠️ Agent 失败不影响 push 结果，仅记录错误日志，不回滚 push
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPostReceive(PostReceiveEvent event) {
        // TODO: Phase 4 — 异步 Code Review 流程
        // 1. String diff = gitletService.getDiff(event.getRepoId(), event.getCommitSha1())
        // 2. String reviewResult = codeReviewAgent.review(event.getRepoId(), event.getCommitSha1(), diff)
        // 3. 解析 JSON → 写入 review_comment 表
        // 4. WebSocket 推送给仓库成员
        // try-catch 包裹全流程，异常只记日志，不回滚 push
    }
}
