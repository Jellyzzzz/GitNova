package com.gitnova.gitobject;

import com.gitnova.storage.ObjectStorage;
import com.gitnova.storage.ObjectStorageException;
import org.springframework.stereotype.Service;

/** Reads canonical Git objects from storage without an exists-then-read race. */
@Service
public class ObjectStorageGitObjectReader implements GitObjectReader {

    private final ObjectStorage objectStorage;
    private final GitObjectCodec gitObjectCodec;

    public ObjectStorageGitObjectReader(ObjectStorage objectStorage, GitObjectCodec gitObjectCodec) {
        this.objectStorage = objectStorage;
        this.gitObjectCodec = gitObjectCodec;
    }

    @Override
    public CommitObject requireCommit(String repoKey, String sha1) {
        byte[] commit = requireRawObject(repoKey, sha1);
        try {
            return gitObjectCodec.decodeCommit(commit);
        } catch (CommitCodecException exception) {
            throw new GitObjectReadException(
                    GitObjectReadException.Reason.CORRUPT,
                    "Object is not a valid canonical commit",
                    exception
            );
        }
    }

    @Override
    public byte[] requireBlob(String repoKey, String sha1) {
        return requireRawObject(repoKey, sha1);
    }

    private byte[] requireRawObject(String repoKey, String sha1) {
        if (repoKey == null || repoKey.isBlank()) {
            throw new IllegalArgumentException("repoKey must not be blank");
        }
        GitObjectId id = GitObjectId.of(sha1);
        try {
            return objectStorage.readObject(repoKey, id.value());
        } catch (ObjectStorageException exception) {
            throw switch (exception.reason()) {
                case NOT_FOUND -> new GitObjectReadException(
                        GitObjectReadException.Reason.NOT_FOUND,
                        "Object not found: " + id.value(), exception
                );
                case CORRUPT -> new GitObjectReadException(
                        GitObjectReadException.Reason.CORRUPT,
                        "Object storage returned corrupt content", exception
                );
                case TRANSIENT -> new GitObjectReadException(
                        GitObjectReadException.Reason.TRANSIENT,
                        "Failed to read object", exception
                );
            };
        } catch (RuntimeException exception) {
            throw new GitObjectReadException(
                    GitObjectReadException.Reason.TRANSIENT,
                    "Failed to read object", exception
            );
        }
    }
}
