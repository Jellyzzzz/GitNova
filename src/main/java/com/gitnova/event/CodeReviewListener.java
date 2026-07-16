package com.gitnova.event;

import com.gitnova.service.agent.CodeReviewAgentLoop;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Code Review 异步监听器 — Phase 4 核心
 *
 * 监听 PostReceiveEvent，在事务提交后异步执行 ReAct Agent Loop，
 * 不阻塞 push 主流程。
 *
 * 💡 Design Note — Spring Event + ReAct Agent 的组合：
 * Spring Event 解决"何时触发"（push 成功后异步），
 * ReAct Agent 解决"如何审查"（自主推理 + 工具调用），两者正交。
 */
@Component
public class CodeReviewListener {

    private static final Logger log = LoggerFactory.getLogger(CodeReviewListener.class);

    private final CodeReviewAgentLoop agentLoop;

    public CodeReviewListener(CodeReviewAgentLoop agentLoop) {
        this.agentLoop = agentLoop;
    }

    /**
     * 异步执行 ReAct Code Review
     *
     * @TransactionalEventListener(phase = AFTER_COMMIT) 确保 CAS 事务提交成功后执行
     * @Async 使用独立线程池，不阻塞 push 响应
     *
     * ⚠️ Agent 失败不影响 push 结果，仅记录错误日志，不回滚 push
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPostReceive(PostReceiveEvent event) {
        try {
            log.info("Starting ReAct Agent review for repo={} commit={}",
                     event.getRepoId(), event.getCommitSha1());
            String reviewResult = agentLoop.runAgentLoop(event.getRepoId(), event.getCommitSha1());
            log.info("ReAct Agent review completed for commit={}: {}", event.getCommitSha1(), reviewResult);
        } catch (Exception e) {
            log.error("ReAct Agent review failed for commit={}, push result unaffected",
                      event.getCommitSha1(), e);
        }
    }
}
