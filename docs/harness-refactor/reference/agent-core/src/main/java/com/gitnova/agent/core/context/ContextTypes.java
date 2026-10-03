package com.gitnova.agent.core.context;

import java.util.List;
import com.gitnova.agent.core.model.ModelTypes;
public final class ContextTypes {
 private ContextTypes() {}
 public record Position(long epoch, long sequence) {}
 public record Group(Position first, Position last, String taskId, List<ModelTypes.Message> messages) {}
 public record Summary(String text, Position coveredThrough, String configDigest) {}
 public record HistorySnapshot(List<Group> groups, Summary summary, Position through) {}
 public record Measurement(long estimatedInputTokens, long fixedTokens, long reservedOutputTokens) {}
}
