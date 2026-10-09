package com.gitnova.agent.core.history;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Read-only history port; neither Core nor ordinary tools supply platform task identity. */
public interface SessionLog {
 record Position(String streamId, long sequence) {}
 record Boundary(Position through, long byteOffset) {}
 record Entry(String eventId, Position position, String executionId, String type,
              Instant occurredAt, Map<String,Object> payload) {}
 List<Entry> read(Position after, Position through, int limit) throws IOException;
 Boundary boundary() throws IOException;
 /** Must re-check the committed boundary under the append lock before awaiting. */
 boolean awaitAfter(Position after, Duration timeout) throws InterruptedException;

 /** A bound view of the same store, not a second log or a best-effort notification callback. */
 interface Writer extends AutoCloseable {
  String executionId();
  Entry append(String type, Map<String,Object> payload) throws IOException;
  /** Revokes this execution's write handle without closing the shared store. */
  void close() throws IOException;
 }
}
