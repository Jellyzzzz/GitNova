package com.gitnova.storage;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.Set;

public interface ObjectStorage {

    void writeObject(String repoKey, String sha1, byte[] content);

    /** Promotes a decoder-owned, already-validated temporary object without buffering it in memory. */
    void promoteObject(String repoKey, String sha1, Path verifiedTemporaryFile);

    byte[] readObject(String repoKey, String sha1);

    /** Opens a raw object stream for bounded consumers. The caller must close it. */
    InputStream openObject(String repoKey, String sha1);

    boolean existsObject(String repoKey, String sha1);

    Set<String> listObjects(String repoKey);
}
