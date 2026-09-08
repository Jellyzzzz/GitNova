package com.gitnova.controller;


import com.gitnova.common.UserContext;
import com.gitnova.dto.ApiResponse;
import com.gitnova.entity.Repository;
import com.gitnova.service.RepositoryAccessService;
import com.gitnova.service.agent.execution.AgentExecutionPersistenceException;
import com.gitnova.service.agent.execution.AgentTaskRunStore;
import com.gitnova.service.agent.task.AgentTaskService;
import com.gitnova.storage.RepoKey;
import org.springframework.web.bind.annotation.*;

import java.util.Objects;

@RestController
@RequestMapping("api/repos/{repoId}/agent/sessions/{sessionId}/tasks")
public class AgentTaskController {
    private final RepositoryAccessService repositoryAccessService;
    private final AgentTaskService taskService;
    public AgentTaskController(RepositoryAccessService repositoryAccessService,AgentTaskService taskService){
        this.taskService= Objects.requireNonNull(taskService,"taskService must not be null");
        this.repositoryAccessService=Objects.requireNonNull(repositoryAccessService,"repositoryAccessService must not be null");
    }

    @PostMapping
    public ApiResponse<TaskResponse>create(
            @PathVariable Long repoId,
            @PathVariable String sessionId,
            @RequestHeader("Idempotency-key") String idempotencyKey,
            @RequestBody CreateTaskRequest request
    ){
        Long actorId=UserContext.getUserId();
        if(actorId==null||actorId<=0){
            throw new IllegalStateException("Authenticated actor is missing");
        }

        Repository repository=repositoryAccessService.requireWriteAccess(repoId,actorId);
        RepoKey repoKey =RepoKey.of(repository.getOwnerId(),repository.getId());
        String message= request.message();
        AgentTaskRunStore.CreateResult result = taskService.create(repoKey,sessionId,actorId,message,idempotencyKey);
        return ApiResponse.success(TaskResponse.from(result));
    }

    public record CreateTaskRequest(String message){
        public CreateTaskRequest{
            requireNonBlank(message,"message");
        }
        public void requireNonBlank(String value,String field){
            Objects.requireNonNull(value,field+" must not be null");
            if(value.isBlank()) throw new IllegalArgumentException(field+" must not be blank");
        }
    }
    public record TaskResponse(String taskId,String sessionId,String initialRunId,String taskStatus,String runStatus,long runNumber,boolean created){
        private static TaskResponse from(AgentTaskRunStore.CreateResult result){
            return new TaskResponse(
                    result.task().taskId(),
                    result.task().sessionId(),
                    result.initialRun().runId(),
                    result.task().status().name(),
                    result.initialRun().status().name(),
                    result.initialRun().runNumber(),
                    result.created()
            );
        }
    }

}
