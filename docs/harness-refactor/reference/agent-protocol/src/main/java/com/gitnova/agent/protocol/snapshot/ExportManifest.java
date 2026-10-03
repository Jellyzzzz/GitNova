package com.gitnova.agent.protocol.snapshot;

import java.util.List;
/** Entries sorted lexicographically by normalized UTF-8 path, bytes are never normalized. */
public record ExportManifest(int schemaVersion, String exportId, String sessionId,
 String taskId, String attemptId, long runnerEpoch, long throughSequence,
 String baseCommit, String expectedPublishedHead, String executionOutcome,
 String treeDigest, String stateDigest, String runtimeConfigDigest, String workerImage,
 List<Entry> entries) {
 public record Entry(String path, long size, String sha256, Kind kind, boolean executable) {}
 public enum Kind { TREE, STATE, BASELINE }
}
