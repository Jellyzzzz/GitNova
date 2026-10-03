package com.gitnova.agent.core.history;

import com.gitnova.agent.protocol.event.AgentEvent;
import com.gitnova.agent.protocol.event.EventType;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
public interface SessionLog extends AutoCloseable {
 record Boundary(long epoch, long sequence, long byteOffset) {}
 AgentEvent append(EventType type, String taskId, String attemptId, Map<String,Object> payload) throws IOException;
 List<AgentEvent> read(long epoch, long after, long through, int limit) throws IOException;
 Boundary boundary() throws IOException;
 /** Must re-check lastSequence under the append lock before awaiting. */
 boolean awaitAfter(long sequence, Duration timeout) throws InterruptedException;
 void close() throws IOException;
}
