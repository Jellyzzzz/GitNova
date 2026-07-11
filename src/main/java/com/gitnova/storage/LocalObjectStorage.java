package com.gitnova.storage;

import com.gitnova.gitlet.GitletException;
import com.gitnova.gitlet.Utils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.Collections;
import java.util.*;

@Component
@ConditionalOnProperty(name = "gitnova.storage.type", havingValue = "local", matchIfMissing = true)
public class LocalObjectStorage implements ObjectStorage{
    @Value("${gitnova.repo.base-path}")
    private String basePath;
    private File resolveObjectFile(String repoKey, String sha1) {
        return Utils.join(basePath, repoKey, ".gitlet", "objects", sha1);
    }

    @Override
    public void writeObject(String repoKey, String sha1, byte[] content) {
        File file=resolveObjectFile(repoKey,sha1);
        if(!file.getParentFile().exists()) file.getParentFile().mkdirs();
        Utils.writeContents(file, (Object) content);
    }

    @Override
    public byte[] readObject(String repoKey, String sha1) {
        File file=resolveObjectFile(repoKey,sha1);
        if(!file.exists()) throw new GitletException("Object not found: "+sha1);
        return Utils.readContents(file);
    }

    @Override
    public boolean existsObject(String repoKey, String sha1) {
        return resolveObjectFile(repoKey,sha1).exists();
    }

    @Override
    public Set<String> listObjects(String repoKey) {
        File dir = Utils.join(basePath, repoKey, ".gitlet", "objects");
        List<String> files = Utils.plainFilenamesIn(dir);
        return files != null ? new HashSet<>(files) : Collections.emptySet();
    }
}
