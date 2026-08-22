package com.gitnova.service.agent.workspace;

import java.util.Objects;

public class WorkspaceProvisionException extends RuntimeException {
    public enum Reason{
      SNAPSHOT_NOT_FOUND,
      SNAPSHOT_CORRUPT,
      STORAGE_UNAVAILABLE,
      INVALID_REPOSITORY_PATH,
      PATH_COLLISION,
      WORKSPACE_CONFLICT,
      FILESYSTEM_FAILURE,
      ATOMIC_PUBLISH_UNAVAILABLE
    }
    private final Reason reason;
    public WorkspaceProvisionException(String message,Reason reason){
        super(message);
        this.reason= Objects.requireNonNull(reason,"reason must not be null");
    }
    public WorkspaceProvisionException(String message,Reason reason,Throwable cause){
      super(message,cause);
      this.reason=Objects.requireNonNull(reason,"reason must not be null");
    }
    public Reason reason(){
      return reason;
    }
}
