package bundlesync;

public final class PathPolicy {
    public static String requireFile(String path) {
        if (path == null || path.isBlank() || path.startsWith("/") || path.contains("\\")
                || path.indexOf('\0') >= 0 || path.matches("^[A-Za-z]:.*")) {
            throw new IllegalArgumentException("invalid repository path");
        }
        for (String part : path.split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) {
                throw new IllegalArgumentException("invalid path segment");
            }
        }
        return path;
    }
}
