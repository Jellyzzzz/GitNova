package bundlesync;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;

public final class SnapshotPublisher {
    @FunctionalInterface
    public interface BlobSource { InputStream open(String sha256) throws IOException; }

    public Path publish(Path parent, String name, List<FileEntry> entries, BlobSource source) throws IOException {
        PathPolicy.requireFile(name);
        if (name.contains("/")) throw new IllegalArgumentException("snapshot name must be one segment");
        FileLayout.validate(entries);
        Files.createDirectories(parent);
        Path target = parent.resolve(name);
        if (Files.exists(target)) throw new IOException("snapshot already exists");
        Path staging = Files.createTempDirectory(parent, ".staging-");
        try {
            for (FileEntry entry : entries) {
                Path file = staging.resolve(entry.path());
                Files.createDirectories(file.getParent());
                try (InputStream input = source.open(entry.sha256()); OutputStream output = Files.newOutputStream(file)) {
                    new BlobCopy().copy(input, output, entry.length(), entry.sha256());
                }
            }
            return Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            if (Files.exists(staging)) {
                try (var paths = Files.walk(staging)) {
                    for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
                }
            }
        }
    }
}
