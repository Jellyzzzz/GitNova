package com.gitnova.service.agent.workspace;

import java.util.Objects;
import java.util.UUID;

public record WorkspaceId(UUID value) {
    public WorkspaceId {
        Objects.requireNonNull(value,"WorkspaceId must not be null");
    }

    public static WorkspaceId generate(){
        return new WorkspaceId(UUID.randomUUID());
    }
    public static WorkspaceId parse(String value){
        Objects.requireNonNull(value,"value must not be null");
        try{
            return new WorkspaceId(UUID.fromString(value));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("workspaceId must be a valid UUID",exception);
        }

    }
    @Override
    public String toString(){
        return value.toString();
    }
}
