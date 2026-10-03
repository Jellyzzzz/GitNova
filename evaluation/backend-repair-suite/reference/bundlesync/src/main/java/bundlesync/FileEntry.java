package bundlesync;

public record FileEntry(String path, long length, String sha256) {
    public FileEntry {
        if (length < 0 || sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("invalid blob metadata");
        }
    }
}
