package com.gitnova.agent.core.process;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import com.gitnova.agent.core.history.ResultStore;
import com.gitnova.agent.core.engine.ExecutionControl;
public interface ProcessSupervisor extends AutoCloseable {
 record Spec(String processId, List<String> argv, Path cwd, Map<String,String> environment,
   Duration timeout, long captureLimit) {}
 record Exit(Integer exitCode, boolean timedOut, boolean cancelled, boolean stoppedConfirmed,
   ResultStore.Ref stdout, ResultStore.Ref stderr, long durationMillis) {}
 record Background(String processId, long startedAtMillis) {}
 Exit run(Spec spec, ExecutionControl control) throws IOException, InterruptedException;
 Background start(Spec spec, ExecutionControl control) throws IOException;
 Map<String,Object> poll(String processId, long afterBytes) throws IOException;
 void stop(String processId) throws IOException, InterruptedException;
 void stopAllAndConfirm() throws IOException, InterruptedException;
 void close() throws IOException;
}
