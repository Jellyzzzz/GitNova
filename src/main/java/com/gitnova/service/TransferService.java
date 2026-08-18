package com.gitnova.service;

import com.gitnova.entity.Branch;
import com.gitnova.entity.CommitRecord;
import com.gitnova.event.PostReceiveEvent;
import com.gitnova.gitobject.CommitCodecException;
import com.gitnova.gitobject.CommitObject;
import com.gitnova.gitobject.GitObjectCodec;
import com.gitnova.gitobject.GitObjectId;
import com.gitnova.mapper.BranchMapper;
import com.gitnova.mapper.CommitRecordMapper;
import com.gitnova.mapper.RepositoryMapper;
import com.gitnova.storage.ObjectStorage;
import com.gitnova.storage.ObjectStorageException;
import com.gitnova.transfer.ObjectPackDecoder;
import com.gitnova.transfer.TransferProperties;
import com.gitnova.transfer.ValidatedPack;
import com.gitnova.transfer.ValidatedObject;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Hosted push flow: stream-validate objects first, then publish a branch CAS last. */
@Service
public class TransferService {

    private static final String DEFAULT_BRANCH = "main";
    private static final int MAX_ANCESTOR_WALK = 100_000;

    private final RepositoryMapper repositoryMapper;
    private final CommitRecordMapper commitRecordMapper;
    private final BranchMapper branchMapper;
    private final ObjectStorage objectStorage;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectPackDecoder packDecoder;
    private final TransferProperties transferProperties;
    private final GitObjectCodec gitObjectCodec;

    public TransferService(RepositoryMapper repositoryMapper,
                           CommitRecordMapper commitRecordMapper,
                           BranchMapper branchMapper,
                           ObjectStorage objectStorage,
                           ApplicationEventPublisher eventPublisher,
                           ObjectPackDecoder packDecoder,
                           TransferProperties transferProperties,
                           GitObjectCodec gitObjectCodec) {
        this.repositoryMapper = repositoryMapper;
        this.commitRecordMapper = commitRecordMapper;
        this.branchMapper = branchMapper;
        this.objectStorage = objectStorage;
        this.eventPublisher = eventPublisher;
        this.packDecoder = packDecoder;
        this.transferProperties = transferProperties;
        this.gitObjectCodec = gitObjectCodec;
    }

    /** Compatibility entry point; production controllers must call the InputStream overload. */
    public int unpackAndStore(String repoKey, byte[] objectsPack) {
        Objects.requireNonNull(objectsPack, "objectsPack must not be null");
        return unpackAndStore(repoKey, new ByteArrayInputStream(objectsPack), objectsPack.length);
    }

    public int unpackAndStore(String repoKey, InputStream objectsPack, long declaredPackSize) {
        try (ValidatedPack pack = packDecoder.decode(objectsPack, declaredPackSize, transferProperties)) {
            for (ValidatedObject object : pack.objects()) {
                objectStorage.promoteObject(repoKey, object.id().value(), object.temporaryFile());
            }
            return pack.objects().size();
        }
    }

    /**
     * Makes a new branch head externally visible only after the target and all
     * required object references validate. Filesystem writes are deliberately
     * outside the database transaction: unreachable content-addressed objects
     * are safe, while a branch CAS is the visibility boundary.
     */
    @Transactional
    public void updateHead(Long repoId, String repoKey, String baseHeadSha1, String newHeadSha1,
                           String branchName, Long authorId, boolean requestReview) {
        Objects.requireNonNull(repoId, "repoId must not be null");
        Objects.requireNonNull(authorId, "authorId must not be null");
        String validatedBranch = BranchName.requireValid(branchName == null ? DEFAULT_BRANCH : branchName);
        GitObjectId targetId = GitObjectId.of(newHeadSha1);
        String expectedBase = normalizeOptionalObjectId(baseHeadSha1);
        CommitObject targetCommit = requireCanonicalCommit(repoKey, targetId);
        requireReferencedBlobs(repoKey, targetCommit);

        String currentHead = branchMapper.findHead(repoId, validatedBranch);
        if (currentHead == null) {
            if (expectedBase != null) {
                throw nonFastForward();
            }
            requireDescendsFrom(repoKey, targetId, null);
            createFirstBranchHead(repoId, validatedBranch, targetId.value());
        } else {
            GitObjectId currentId;
            try {
                currentId = GitObjectId.of(currentHead);
            } catch (IllegalArgumentException exception) {
                throw new TransferRejectedException(TransferRejectedException.Reason.CORRUPT_OBJECT,
                        "branch head is not a valid Git object ID", exception);
            }
            if (!currentId.value().equals(expectedBase)) {
                throw nonFastForward();
            }
            if (currentId.equals(targetId)) {
                return;
            }
            requireDescendsFrom(repoKey, targetId, currentId);
            if (branchMapper.compareAndSetHead(repoId, validatedBranch, currentId.value(), targetId.value()) != 1) {
                throw nonFastForward();
            }
        }

        writeCommitRecordIfAbsent(repoId, validatedBranch, targetId, targetCommit, authorId);
        if (DEFAULT_BRANCH.equals(validatedBranch)) {
            repositoryMapper.updateDefaultBranchHeadCache(repoId, targetId.value());
        }
        eventPublisher.publishEvent(new PostReceiveEvent(
                this, repoId, expectedBase, targetId.value(), authorId, requestReview
        ));
    }

    private void createFirstBranchHead(Long repoId, String branchName, String targetHead) {
        Branch branch = new Branch();
        branch.setRepoId(repoId);
        branch.setName(branchName);
        branch.setHeadCommit(targetHead);
        try {
            if (branchMapper.insert(branch) != 1) {
                throw nonFastForward();
            }
        } catch (DuplicateKeyException exception) {
            throw nonFastForward();
        }
    }

    private void writeCommitRecordIfAbsent(Long repoId, String branchName, GitObjectId targetId,
                                           CommitObject commit, Long authorId) {
        CommitRecord record = new CommitRecord();
        record.setSha1(targetId.value());
        record.setRepoId(repoId);
        record.setParentSha1(commit.parentSha1().map(GitObjectId::value).orElse(null));
        record.setMessage(commit.message());
        record.setAuthorId(authorId);
        record.setBranchName(branchName);
        record.setCreatedAt(LocalDateTime.ofInstant(commit.timestamp(), ZoneOffset.UTC));
        commitRecordMapper.insertIfAbsent(record);
    }

    private void requireReferencedBlobs(String repoKey, CommitObject commit) {
        for (GitObjectId blobId : commit.mapping().values()) {
            if (!objectStorage.existsObject(repoKey, blobId.value())) {
                throw new TransferRejectedException(TransferRejectedException.Reason.MISSING_OBJECT,
                        "commit references a missing blob: " + blobId.value());
            }
        }
    }

    private void requireDescendsFrom(String repoKey, GitObjectId target, GitObjectId expectedBase) {
        Set<GitObjectId> visited = new HashSet<>();
        GitObjectId current = target;
        for (int depth = 0; depth < MAX_ANCESTOR_WALK; depth++) {
            if (expectedBase != null && current.equals(expectedBase)) {
                return;
            }
            if (!visited.add(current)) {
                throw new TransferRejectedException(TransferRejectedException.Reason.CORRUPT_OBJECT,
                        "commit ancestry contains a cycle");
            }
            CommitObject commit = requireCanonicalCommit(repoKey, current);
            Optional<GitObjectId> parent = commit.parentSha1();
            if (parent.isEmpty()) {
                if (expectedBase == null) {
                    return;
                }
                throw nonFastForward();
            }
            current = parent.orElseThrow();
        }
        throw new TransferRejectedException(TransferRejectedException.Reason.CORRUPT_OBJECT,
                "commit ancestry exceeds traversal limit");
    }

    private CommitObject requireCanonicalCommit(String repoKey, GitObjectId id) {
        try {
            byte[] content = objectStorage.readObject(repoKey, id.value());
            return gitObjectCodec.decodeCommit(content);
        } catch (ObjectStorageException exception) {
            if (exception.reason() == ObjectStorageException.Reason.NOT_FOUND) {
                throw new TransferRejectedException(TransferRejectedException.Reason.MISSING_OBJECT,
                        "commit object is missing: " + id.value(), exception);
            }
            if (exception.reason() == ObjectStorageException.Reason.CORRUPT) {
                throw new TransferRejectedException(TransferRejectedException.Reason.CORRUPT_OBJECT,
                        "commit object is corrupt: " + id.value(), exception);
            }
            throw exception;
        } catch (CommitCodecException exception) {
            throw new TransferRejectedException(TransferRejectedException.Reason.CORRUPT_OBJECT,
                    "object is not a valid canonical commit: " + id.value(), exception);
        }
    }

    private static String normalizeOptionalObjectId(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return GitObjectId.of(value).value();
    }

    private static TransferRejectedException nonFastForward() {
        return new TransferRejectedException(TransferRejectedException.Reason.NON_FAST_FORWARD,
                "non-fast-forward: remote branch advanced or the target does not descend from its HEAD");
    }
}
