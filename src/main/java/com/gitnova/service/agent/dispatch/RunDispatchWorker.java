package com.gitnova.service.agent.dispatch;

import com.gitnova.service.agent.execution.AgentRun;
import com.gitnova.service.agent.execution.AgentTaskRunStore;
import com.gitnova.service.agent.execution.DurableRunExecutor;
import com.rabbitmq.client.Channel;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;

import java.io.IOException;
import java.util.UUID;

public class RunDispatchWorker {
    private static final int LEASE_SECONDS = 30;

    private final String workerId = UUID.randomUUID().toString();
    private final AgentTaskRunStore taskRunStore;
    private final DurableRunExecutor runExecutor;

    public RunDispatchWorker(
            AgentTaskRunStore taskRunStore,
            DurableRunExecutor runExecutor
    ) {
        this.taskRunStore = taskRunStore;
        this.runExecutor = runExecutor;
    }

    @RabbitListener(
            queues = AgentRabbitConfiguration.RUN_QUEUE,
            ackMode = "MANUAL"
    )
    public void consume(
            RunDispatchMessage message,
            Channel channel,
            @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag
    ) throws IOException {
        AgentRun run = taskRunStore.findRun(message.runId()).orElse(null);
        if (run == null) {
            channel.basicAck(deliveryTag, false);
            return;
        }
        if (run.status() != AgentRun.Status.QUEUED) {
            channel.basicAck(deliveryTag, false);
            return;
        }

        AgentTaskRunStore.ClaimResult claim = taskRunStore.claimRun(
                new AgentTaskRunStore.ClaimCommand(
                        "run:claimed:" + run.runId() + ":" + workerId,
                        run.sessionId(),
                        run.taskId(),
                        run.runId(),
                        workerId,
                        LEASE_SECONDS
                )
        );
        if (claim.disposition() != AgentTaskRunStore.ClaimDisposition.CLAIMED) {
            channel.basicAck(deliveryTag, false);
            return;
        }

        AgentRun claimedRun = claim.run();
        channel.basicAck(deliveryTag, false);
        runExecutor.execute(
                claimedRun.runId(),
                workerId,
                claimedRun.currentFencingToken()
        );
    }
}
