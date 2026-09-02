package com.gitnova.service.agent.journal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gitnova.service.agent.persistence.AgentEventAppender;
import com.gitnova.service.agent.persistence.AgentStepType;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;


import java.util.Objects;
@Component
public class DefaultRunJournal implements RunJournal{
    private final AgentEventAppender appender;
    private final ObjectMapper objectMapper;
    public DefaultRunJournal(AgentEventAppender appender, ObjectMapper objectMapper){
        this.appender=appender;
        this.objectMapper=objectMapper;
    }
    @Override
    @Transactional
    public AgentEventAppender.AppendResult appendModelCallStarted(RunJournalScope scope,ModelCallStartedPayload payload){
        Objects.requireNonNull(scope, "scope must not be null");
        Objects.requireNonNull(payload, "payload must not be null");
        ObjectNode persistedPayload =objectMapper.createObjectNode();
        String eventId="run:"+ scope.runId()+":model-call:"+payload.modelCallId()+":started";

        persistedPayload.put("modelCallId",payload.modelCallId());
        persistedPayload.put(
                "requestDigest",
                payload.requestDigest()
        );
        persistedPayload.put(
                "contextThroughRunStepSequence",
                payload.contextThroughRunStepSequence()
        );
        persistedPayload.put(
                "workspaceGeneration",
                payload.workspaceGeneration()
        );
        persistedPayload.put(
                "executionConfigDigest",
                scope.executionConfigDigest()
        );
        persistedPayload.put("eventId",eventId);

        AgentEventAppender.AppendCommand command=new AgentEventAppender.AppendCommand(eventId, scope.sessionId(), scope.taskId(), scope.runId(), AgentStepType.MODEL_CALL_STARTED,1,persistedPayload, null, scope.runId(), null,null);
        AgentEventAppender.RunExecutionAuthority authority=new AgentEventAppender.RunExecutionAuthority(scope.fencingToken(), scope.workerId());
        return appender.appendFence(command,authority);
    }

    @Override
    @Transactional
    public AgentEventAppender.AppendResult appendModelResponse(RunJournalScope scope, ModelResponsePayload payload) {
        Objects.requireNonNull(scope,"scope must not be null");
        Objects.requireNonNull(payload,"payload must not be null");
        String causationEventId="run:"+ scope.runId()+":model-call:"+payload.modelCallId()+":started";
        String eventId="run:"+ scope.runId()+":model-call:"+payload.modelCallId()+":response";
        ObjectNode persistedPayload=objectMapper.valueToTree(payload);

        AgentEventAppender.AppendCommand command=new AgentEventAppender.AppendCommand(eventId,scope.sessionId(), scope.taskId(), scope.runId(), AgentStepType.MODEL_RESPONSE,1,persistedPayload, causationEventId, scope.runId(), null,null);
        AgentEventAppender.RunExecutionAuthority authority=new AgentEventAppender.RunExecutionAuthority(scope.fencingToken(), scope.workerId());
        return appender.appendFence(command,authority);
    }
}
