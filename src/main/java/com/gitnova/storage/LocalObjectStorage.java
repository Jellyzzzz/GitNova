package com.gitnova.storage;

import com.gitnova.gitobject.GitObjectId;
import com.gitnova.storage.config.RepositoryStorageProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

@Component
@ConditionalOnProperty(name = "gitnova.storage.type", havingValue = "local", matchIfMissing = true)
public class LocalObjectStorage implements ObjectStorage{
    private final ObjectPathResolver pathResolver;
    public LocalObjectStorage(RepositoryStorageProperties properties){
        this.pathResolver=new ObjectPathResolver(properties.basePath());
    }

    @Override
    public void writeObject(String repoKey, String sha1, byte[] content) {
        RepoKey key=RepoKey.parseCanonical(repoKey);
        GitObjectId id=GitObjectId.of(sha1);
        Path path=pathResolver.objectPath(key,id);
        Objects.requireNonNull(content, "content must not be null");
        try {
            Files.createDirectories(path.getParent());
            Files.write(path,content);
        } catch (IOException exception) {
            throw new ObjectStorageException("Failed to write object: " + id.value(), exception);
        }
    }

    @Override
    public byte[] readObject(String repoKey, String sha1) {
        RepoKey key=RepoKey.parseCanonical(repoKey);
        GitObjectId id=GitObjectId.of(sha1);
        Path path=pathResolver.objectPath(key,id);
        try {
            return Files.readAllBytes(path);
        } catch (IOException exception) {
            throw new ObjectStorageException("Failed to read object: " + id.value(), exception);
        }
    }

    @Override
    public boolean existsObject(String repoKey, String sha1) {
        RepoKey key=RepoKey.parseCanonical(repoKey);
        GitObjectId id=GitObjectId.of(sha1);
        Path path=pathResolver.objectPath(key,id);
        return Files.exists(path);
    }

    @Override
    public Set<String> listObjects(String repoKey) {
        RepoKey key=RepoKey.parseCanonical(repoKey);
        Path directory =pathResolver.objectDirectory(key);
        if(Files.notExists(directory)) return Set.of();
        Set<String>objectIds=new HashSet<>();
        try (DirectoryStream<Path>entries=Files.newDirectoryStream(directory)) {
            for(Path entry:entries){
                String name=entry.getFileName().toString();
                if (Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)
                        && GitObjectId.isValid(name)) {
                    objectIds.add(name);
                }
            }
        } catch (IOException exception) {
            throw new ObjectStorageException("Failed to list objects for repository: " + key.value(), exception);
        }
        return Set.copyOf(objectIds);
    }
}
