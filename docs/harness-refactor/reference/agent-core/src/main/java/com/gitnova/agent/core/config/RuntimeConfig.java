package com.gitnova.agent.core.config;

public record RuntimeConfig(String model, int maxModelCalls, int maxToolCalls,
 int maxOutputTokens, long contextWindow, long safetyMarginTokens,
 double summaryTrigger, double compactTrigger, double compactTarget,
 int retainGroups, int inlineTokens, int previewTokens, long maxCaptureBytes,
 String thinkingJson, String summaryThinkingJson) {
 public RuntimeConfig {
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
