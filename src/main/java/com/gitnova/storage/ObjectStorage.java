package com.gitnova.storage;

import java.util.Set;

public interface ObjectStorage {
    void writeObject(String repoKey, String sha1, byte[] content);
    byte[] readObject(String repoKey, String sha1);
    boolean existsObject(String repoKey, String sha1);
    Set<String> listObjects(String repoKey);
}
