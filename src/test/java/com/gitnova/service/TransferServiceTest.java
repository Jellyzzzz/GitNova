package com.gitnova.service;

import com.gitnova.gitobject.CanonicalGitObjectCodec;
import com.gitnova.gitobject.GitObjectHasher;
import com.gitnova.storage.FakeObjectStorage;
import com.gitnova.storage.config.RepositoryStorageProperties;
import com.gitnova.transfer.StreamingObjectPackDecoder;
import com.gitnova.transfer.TransferProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransferServiceTest {

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
