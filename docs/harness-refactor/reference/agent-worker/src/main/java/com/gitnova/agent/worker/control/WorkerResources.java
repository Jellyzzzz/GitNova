package com.gitnova.agent.worker.control;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import com.gitnova.agent.protocol.command.AgentCommand;
/** The HTTP shell depends on this port; no dependency on the later concrete ZIP/store implementation. */
public interface WorkerResources {
 record ObjectFile(String objectId, Path path, String sha256, long bytes) {}
 ObjectFile receiveBootstrap(String bootstrapId, String expectedSha256, InputStream body, long limit) throws IOException;
 void initialize(String commandId, AgentCommand.Initialize init) throws IOException;
 ObjectFile export(String exportId) throws IOException;
 String previewJson(String commandId) throws IOException;
 void checkpoint(String commandId, AgentCommand.Checkpoint request);
 void acknowledgeCheckpoint(AgentCommand.CheckpointAck request);
 void stopWorker(AgentCommand.StopWorker request);
}
