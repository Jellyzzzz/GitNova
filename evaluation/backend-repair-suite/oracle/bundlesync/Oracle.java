package bundlesync;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import static testing.Checks.*;

public final class Oracle {
    private static String digest(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    public static void main(String[] args) throws Exception {
        String hash = digest(new byte[0]);
        for (String invalid : new String[]{"", " ", "/root", "../x", "a/../b", "a/./b", "a//b", "a/",
                "C:/x", "C:x", "a\\b", "a/../../b", "a/\0b"}) {
            test("path/reject/" + invalid.replace('\0', '?'), () -> rejects(() -> PathPolicy.requireFile(invalid)));
        }
        for (String valid : new String[]{"a", "a/b.txt", "a..b/file", ".config", "中文/资料.txt", "file name.txt"}) {
            test("path/allow/" + valid, () -> equal(valid, PathPolicy.requireFile(valid)));
        }
        for (int length : new int[]{0, 1, 8, 4095, 4096, 4097, 65537}) {
            for (int chunk : new int[]{1, 3, 1023, 4096}) {
                test("copy/short-read/" + length + "/" + chunk, () -> {
                    byte[] bytes = new byte[length];
                    for (int index = 0; index < length; index++) bytes[index] = (byte) (index * 31 + 7);
                    boolean[] closed = {false, false};
                    ByteArrayInputStream input = new ByteArrayInputStream(bytes) {
                        @Override public synchronized int read(byte[] b, int off, int len) { return super.read(b, off, Math.min(chunk, len)); }
                        @Override public void close() { closed[0] = true; }
                    };
                    ByteArrayOutputStream output = new ByteArrayOutputStream() {
                        @Override public void close() { closed[1] = true; }
                    };
                    new BlobCopy().copy(input, output, length, digest(bytes));
                    equal(true, Arrays.equals(bytes, output.toByteArray()));
                    equal(false, closed[0]);
                    equal(false, closed[1]);
                    ioFails(() -> new BlobCopy().copy(new ByteArrayInputStream(bytes), new ByteArrayOutputStream(), length + 1, digest(bytes)));
                    ioFails(() -> new BlobCopy().copy(new ByteArrayInputStream(bytes), new ByteArrayOutputStream(), length, "0".repeat(64)));
                    if (length > 0) ioFails(() -> new BlobCopy().copy(new ByteArrayInputStream(bytes), new ByteArrayOutputStream(), length - 1, digest(bytes)));
                });
            }
        }
        for (int depth = 1; depth <= 12; depth++) {
            String prefix = "x/".repeat(depth);
            test("layout/all-ancestors/" + depth, () -> {
                FileEntry parent = new FileEntry(prefix + "a", 0, hash);
                FileEntry unrelated = new FileEntry(prefix + "a-b", 0, hash);
                FileEntry child = new FileEntry(prefix + "a/c", 0, hash);
                rejects(() -> FileLayout.validate(List.of(parent, unrelated, child)));
                rejects(() -> FileLayout.validate(List.of(child, unrelated, parent)));
                rejects(() -> FileLayout.validate(List.of(parent, parent)));
                FileLayout.validate(List.of(unrelated, child));
            });
        }
        for (int sample = 1; sample <= 20; sample++) {
            final int n = sample;
            test("plan/exact-operations/" + sample, () -> {
                FileEntry unchanged = new FileEntry("same", 0, hash);
                List<FileEntry> before = List.of(new FileEntry("old", 0, hash), new FileEntry("changed", 0, hash), unchanged);
                List<FileEntry> after = List.of(new FileEntry("new", 0, hash), new FileEntry("changed", n, "1".repeat(64)), unchanged);
                equal(List.of(new SyncPlan.Operation("DELETE", "old"), new SyncPlan.Operation("PUT", "changed"),
                        new SyncPlan.Operation("PUT", "new")), new SyncPlan().compare(before, after));
                equal(List.of(), new SyncPlan().compare(before, before));
                equal(List.of(new SyncPlan.Operation("DELETE", "a"), new SyncPlan.Operation("PUT", "a/b")),
                        new SyncPlan().compare(List.of(new FileEntry("a", 0, hash)), List.of(new FileEntry("a/b", 0, hash))));
            });
        }
        for (int failAt = 0; failAt < 3; failAt++) {
            final int fail = failAt;
            test("publish/failure-position/" + failAt, () -> {
                Path parent = Files.createTempDirectory("hidden-publish-");
                int[] calls = {0};
                List<FileEntry> entries = List.of(new FileEntry("a", 0, hash), new FileEntry("b/c", 0, hash), new FileEntry("b/d", 0, hash));
                ioFails(() -> new SnapshotPublisher().publish(parent, "snapshot", entries, key -> {
                    if (calls[0]++ == fail) throw new IOException("injected blob read failure");
                    return new ByteArrayInputStream(new byte[0]);
                }));
                try (var children = Files.list(parent)) { equal(0L, children.count()); }
                Path ready = new SnapshotPublisher().publish(parent, "snapshot", entries, key -> new ByteArrayInputStream(new byte[0]));
                equal(true, Files.isRegularFile(ready.resolve("b/d")));
                ioFails(() -> new SnapshotPublisher().publish(parent, "snapshot", entries, key -> new ByteArrayInputStream(new byte[0])));
                equal(true, Files.isRegularFile(ready.resolve("a")));
            });
        }
        test("publish/corrupt-blob", () -> {
            Path parent = Files.createTempDirectory("hidden-publish-");
            ioFails(() -> new SnapshotPublisher().publish(parent, "snapshot", List.of(new FileEntry("a", 1, hash)),
                    key -> new ByteArrayInputStream(new byte[]{42})));
            try (var children = Files.list(parent)) { equal(0L, children.count()); }
        });
        test("publish/validate-layout-before-reading", () -> {
            Path parent = Files.createTempDirectory("hidden-publish-");
            int[] reads = {0};
            rejects(() -> new SnapshotPublisher().publish(parent, "snapshot", List.of(new FileEntry("a", 0, hash), new FileEntry("a/b", 0, hash)),
                    key -> { reads[0]++; return new ByteArrayInputStream(new byte[0]); }));
            equal(0, reads[0]);
            try (var children = Files.list(parent)) { equal(0L, children.count()); }
        });
        finish("ORACLE");
    }
}
