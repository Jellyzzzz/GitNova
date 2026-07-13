package com.gitnova.service;


import com.gitnova.gitlet.Utils ; // 确保引入了你的 Utils.sha1 方法
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

// 这个注解会启动 Spring 容器，帮你把需要的 Service 和配置自动注入进来
@SpringBootTest
public class TransferServiceTest {

    @Autowired
    private TransferService transferService;

    @Test
    public void testUnpackAndStore() throws Exception {
        // 1. 准备要传输的文件内容
        String fileContent = "Hello GitNova! This is a unit test file.";
        byte[] contentBytes = fileContent.getBytes(StandardCharsets.UTF_8);

        // 2. 计算真实 SHA-1
        String sha1 = Utils.sha1(contentBytes);
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

        System.out.println("✅ 单元测试通过！成功解包并写入本地！SHA-1: " + sha1);
    }
}
