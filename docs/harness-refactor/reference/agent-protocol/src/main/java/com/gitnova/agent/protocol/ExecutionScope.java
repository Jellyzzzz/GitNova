package com.gitnova.agent.protocol;
import java.util.Objects;
/** Immutable identity, not an authorization decision. Binding tokens are checked by Server/Worker. */
public record ExecutionScope(String sessionId, String worklineId, long runnerEpoch) {
 public ExecutionScope { Objects.requireNonNull(sessionId); Objects.requireNonNull(worklineId);
  if (sessionId.isBlank() || worklineId.isBlank() || runnerEpoch < 1) throw new IllegalArgumentException("invalid execution scope"); }
 public void requireSame(ExecutionScope other) {
  if (!equals(other)) throw new IllegalArgumentException("SCOPE_MISMATCH");
 }
}
