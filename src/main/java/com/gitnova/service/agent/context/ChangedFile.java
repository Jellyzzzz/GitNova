package com.gitnova.service.agent.context;

public record ChangedFile(String path,String changeType,String Language,int addedLines,int deletedLines,int hunkCount,boolean binary) {
}
