package com.gitnova.service.agent.context;
import java.util.*;
public record DiffManifest(List<ChangedFile>files,int totalFiles,int totalHunks,int totalAddedLines,int totalDeletedLines,boolean containsBinary) {
}
