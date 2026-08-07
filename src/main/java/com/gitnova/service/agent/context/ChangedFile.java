package com.gitnova.service.agent.context;

public record ChangedFile(String path, String changeType, String language, int addedLines, int deletedLines, int hunkCount, boolean binary) {
}
