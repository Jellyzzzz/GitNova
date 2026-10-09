package com.gitnova.agent.core.config;

import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.Collections;

public record RuntimeConfig(String model, int maxModelCalls, int maxToolCalls,
 int maxOutputTokens, long contextWindow, long safetyMarginTokens,
 double summaryTrigger, double compactTrigger, double compactTarget,
 int retainGroups, int inlineTokens, int previewTokens, long maxCaptureBytes,
 String thinkingJson, String summaryThinkingJson, List<String> allowedTools) {
 public RuntimeConfig {
  // Closed tool set for this deployment; extending it requires an implementation and tests.
  Set<String> known = Set.of("shell", "read_file", "edit_file", "history_read", "history_search",
                            "report_progress", "create_pull_request");
  if (allowedTools == null) throw new IllegalArgumentException("allowedTools is required");
  Set<String> seen = new HashSet<>();
  for (String name : allowedTools) {
   if (name == null || !known.contains(name) || !seen.add(name))
    throw new IllegalArgumentException("Unknown or duplicate tool: " + name);
  }
  List<String> sorted = new ArrayList<>(allowedTools);
  Collections.sort(sorted);
  allowedTools = List.copyOf(sorted); // Empty means no tools, never implicit all-tools.

  if (model == null || model.isBlank() || maxModelCalls <= 0 || maxToolCalls <= 0
      || maxOutputTokens <= 0 || contextWindow <= (long) maxOutputTokens + safetyMarginTokens
      || safetyMarginTokens < 0 || retainGroups < 0 || inlineTokens <= 0
      || previewTokens <= 0 || maxCaptureBytes <= 0
      || !(compactTarget > 0 && compactTarget < summaryTrigger
           && summaryTrigger < compactTrigger && compactTrigger < 1)) {
   throw new IllegalArgumentException("Invalid runtime config");
  }
 }
}
