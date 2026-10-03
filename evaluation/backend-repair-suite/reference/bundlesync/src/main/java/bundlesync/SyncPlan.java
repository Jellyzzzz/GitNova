package bundlesync;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class SyncPlan {
    public record Operation(String type, String path) {}

    public List<Operation> compare(List<FileEntry> before, List<FileEntry> after) {
        FileLayout.validate(before);
        FileLayout.validate(after);
        Map<String, FileEntry> oldFiles = new TreeMap<>();
        Map<String, FileEntry> newFiles = new TreeMap<>();
        for (FileEntry entry : before) oldFiles.put(entry.path(), entry);
        for (FileEntry entry : after) newFiles.put(entry.path(), entry);
        List<Operation> operations = new ArrayList<>();
        for (String path : oldFiles.keySet()) {
            if (!newFiles.containsKey(path)) operations.add(new Operation("DELETE", path));
        }
        for (FileEntry entry : newFiles.values()) {
            if (!entry.equals(oldFiles.get(entry.path()))) operations.add(new Operation("PUT", entry.path()));
        }
        return operations;
    }
}
