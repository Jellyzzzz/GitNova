package bundlesync;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class FileLayout {
    public static void validate(List<FileEntry> entries) {
        Set<String> paths = new HashSet<>();
        for (FileEntry entry : entries) {
            if (!paths.add(PathPolicy.requireFile(entry.path()))) throw new IllegalArgumentException("duplicate path");
        }
        for (String path : paths) {
            int slash = path.indexOf('/');
            while (slash >= 0) {
                if (paths.contains(path.substring(0, slash))) throw new IllegalArgumentException("file/directory collision");
                slash = path.indexOf('/', slash + 1);
            }
        }
    }
}
