package com.gitnova.service;

import com.gitnova.gitobject.CanonicalGitObjectCodec;
import com.gitnova.gitobject.CommitObject;
import com.gitnova.gitobject.GitObjectId;
import com.gitnova.gitobject.GitObjectHasher;
import com.gitnova.mapper.BranchMapper;
import com.gitnova.mapper.CommitRecordMapper;
import com.gitnova.mapper.RepositoryMapper;
import com.gitnova.storage.FakeObjectStorage;
import com.gitnova.storage.config.RepositoryStorageProperties;
import com.gitnova.transfer.StreamingObjectPackDecoder;
import com.gitnova.transfer.TransferProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.util.unit.DataSize;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TransferServiceTest {

    @Test
    void shouldTreatAnAlreadyPublishedTargetAsAnIdempotentRetry() {
        String repoKey = "7/42";
        CanonicalGitObjectCodec codec = new CanonicalGitObjectCodec();
        CommitObject commit = new CommitObject(
                Optional.empty(),
                Instant.parse("2026-09-06T00:00:00Z"),
                "initial commit",
                Map.of()
        );
        byte[] commitBytes = codec.encodeCommit(commit);
        GitObjectId target = GitObjectHasher.sha1(commitBytes);
        FakeObjectStorage objectStorage = new FakeObjectStorage();
        objectStorage.writeObject(repoKey, target.value(), commitBytes);
        RepositoryMapper repositoryMapper = mock(RepositoryMapper.class);
        CommitRecordMapper commitRecordMapper = mock(CommitRecordMapper.class);
        BranchMapper branchMapper = mock(BranchMapper.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        when(branchMapper.findHead(42L, "main")).thenReturn(target.value());
        TransferService transferService = new TransferService(
                repositoryMapper,
                commitRecordMapper,
                branchMapper,
                objectStorage,
                eventPublisher,
                null,
                null,
                codec
        );

        transferService.updateHead(
                42L,
                repoKey,
                null,
                target.value(),
                "main",
                7L,
                false
        );

        verify(branchMapper, never()).compareAndSetHead(any(), any(), any(), any());
        verifyNoInteractions(repositoryMapper, commitRecordMapper, eventPublisher);
    }

    @Test
    void shouldRejectWhenAnotherWriterWinsTheBranchCas() {
        String repoKey = "7/42";
        CanonicalGitObjectCodec codec = new CanonicalGitObjectCodec();
        CommitObject current = new CommitObject(
                Optional.empty(),
                Instant.parse("2026-09-06T00:00:00Z"),
                "current",
                Map.of()
        );
        byte[] currentBytes = codec.encodeCommit(current);
        GitObjectId currentId = GitObjectHasher.sha1(currentBytes);
        CommitObject target = new CommitObject(
                Optional.of(currentId),
                Instant.parse("2026-09-06T00:01:00Z"),
                "target",
                Map.of()
        );
        byte[] targetBytes = codec.encodeCommit(target);
        GitObjectId targetId = GitObjectHasher.sha1(targetBytes);
        FakeObjectStorage objectStorage = new FakeObjectStorage();
        objectStorage.writeObject(repoKey, currentId.value(), currentBytes);
        objectStorage.writeObject(repoKey, targetId.value(), targetBytes);
        RepositoryMapper repositoryMapper = mock(RepositoryMapper.class);
        CommitRecordMapper commitRecordMapper = mock(CommitRecordMapper.class);
        BranchMapper branchMapper = mock(BranchMapper.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        when(branchMapper.findHead(42L, "main")).thenReturn(currentId.value());
        when(branchMapper.compareAndSetHead(
                42L,
                "main",
                currentId.value(),
                targetId.value()
        )).thenReturn(0);
        TransferService transferService = new TransferService(
                repositoryMapper,
                commitRecordMapper,
                branchMapper,
                objectStorage,
                eventPublisher,
                null,
                null,
                codec
        );

        TransferRejectedException exception = assertThrows(
                TransferRejectedException.class,
                () -> transferService.updateHead(
                        42L,
                        repoKey,
                        currentId.value(),
                        targetId.value(),
                        "main",
                        7L,
                        false
                )
        );

        assertEquals(TransferRejectedException.Reason.NON_FAST_FORWARD, exception.reason());
        verifyNoInteractions(repositoryMapper, commitRecordMapper, eventPublisher);
    }

    @Test
    void shouldUnpackAndStoreVerifiedObjectWithoutSpringContext(@TempDir Path tempDirectory) {
        FakeObjectStorage objectStorage = new FakeObjectStorage();
        TransferService transferService = new TransferService(
                null,
                null,
                null,
                objectStorage,
                null,
                new StreamingObjectPackDecoder(
                        new RepositoryStorageProperties(tempDirectory)
                ),
                new TransferProperties(
                        10,
                        DataSize.ofMegabytes(1),
                        DataSize.ofMegabytes(2),
                        DataSize.ofKilobytes(8)
                ),
                new CanonicalGitObjectCodec()
        );

        // 1. 准备要传输的文件内容
        String fileContent = "Hello GitNova! This is a unit test file.";
        byte[] contentBytes = fileContent.getBytes(StandardCharsets.UTF_8);

        // 2. 计算真实 SHA-1
        String sha1 = GitObjectHasher.sha1(contentBytes).value();
        byte[] sha1Bytes = sha1.getBytes(StandardCharsets.UTF_8);

        // 3. 严格按照协议拼装二进制包：[4字节 N] + [40字节 SHA1] + [8字节 长度] + [真实内容]
        int totalSize = 4 + 40 + 8 + contentBytes.length;
        ByteBuffer buffer = ByteBuffer.allocate(totalSize);

        buffer.putInt(1); // N = 1 个对象
        buffer.put(sha1Bytes); // 40 位 SHA-1
        buffer.putLong(contentBytes.length); // 8 位长度 L
        buffer.put(contentBytes); // 真实内容

        byte[] packBytes = buffer.array();

        // 4. 调用你要测试的业务方法！
        String testRepoKey = "test_user/test_repo";
        int storedCount = transferService.unpackAndStore(testRepoKey, packBytes);

        // 5. 断言（Assert）：期待成功解包 1 个对象
        assertEquals(1, storedCount, "解包数量应为 1");
        assertTrue(objectStorage.existsObject(testRepoKey, sha1));
    }
}
