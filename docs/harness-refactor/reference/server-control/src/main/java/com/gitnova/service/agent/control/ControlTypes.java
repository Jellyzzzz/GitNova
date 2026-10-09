package com.gitnova.service.agent.control;

import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import com.gitnova.agent.protocol.snapshot.ExportManifest;
public final class ControlTypes {
 private ControlTypes() {}
 public record LaunchSpec(String sessionId, String worklineId, long epoch, String createOperationId,
  String volumeName, String imageRef, String configDigest, String runtimeConfigJson,
  URI modelEndpoint, String workerToken, String modelToken,
  URI platformEndpoint, String platformToken, Instant expiresAt) {}
 public record Binding(String sessionId, String worklineId, long epoch, String sandboxId, String volumeName,
  String createOperationId, String status, Instant expiresAt) {}
 public record ExpectedScope(String sessionId, String worklineId, String taskId, String attemptId,
  long epoch, String exportId) {}
 public record Limits(long maxBundleBytes,long maxExpandedBytes,long maxFileBytes,
  int maxFiles,int maxPathChars) {}
 public record VerifiedBundle(Path zipPath, ExportManifest manifest,
  String bundleSha256, long bundleBytes) {}
 public record Submission(String taskId,String attemptId,String commandId,boolean duplicate) {}
}
