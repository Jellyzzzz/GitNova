package bundlesync;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import static testing.Checks.*;

public final class PublicChecks {
    private static String digest(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    public static void main(String[] args) throws Exception {
        byte[] content = "hello world".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String hash = digest(content);
        test("path/traversal", () -> rejects(() -> PathPolicy.requireFile("docs/../secret")));
        test("path/valid", () -> equal("docs/中文.txt", PathPolicy.requireFile("docs/中文.txt")));
        test("copy/short-read", () -> {
            ByteArrayInputStream input = new ByteArrayInputStream(content) {
                @Override public synchronized int read(byte[] b, int off, int len) { return super.read(b, off, Math.min(2, len)); }
            };
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            new BlobCopy().copy(input, output, content.length, hash);
            equal(java.util.Arrays.toString(content), java.util.Arrays.toString(output.toByteArray()));
        });
        test("plan/deletion", () -> equal(List.of(new SyncPlan.Operation("DELETE", "old.txt")),
                new SyncPlan().compare(List.of(new FileEntry("old.txt", content.length, hash)), List.of())));
        test("layout/parent-file", () -> rejects(() -> FileLayout.validate(List.of(
                new FileEntry("a", 0, hash), new FileEntry("a/b", 0, hash)))));
        test("publish/failure-cleanup", () -> {
            Path parent = Files.createTempDirectory("publish-test-");
            ioFails(() -> new SnapshotPublisher().publish(parent, "snapshot", List.of(
                    new FileEntry("first.txt", content.length, hash), new FileEntry("second.txt", 0, "0".repeat(64))),
                    key -> { if (!key.equals(hash)) throw new IOException("simulated read failure"); return new ByteArrayInputStream(content); }));
            try (var children = Files.list(parent)) { equal(0L, children.count()); }
        });
        test("publish/success", () -> {
            Path parent = Files.createTempDirectory("publish-test-");
            Path root = new SnapshotPublisher().publish(parent, "snapshot", List.of(new FileEntry("src/a", content.length, hash)),
                    key -> new ByteArrayInputStream(content));
            equal("hello world", Files.readString(root.resolve("src/a")));
        });
        finish("PUBLIC");
    }
}
