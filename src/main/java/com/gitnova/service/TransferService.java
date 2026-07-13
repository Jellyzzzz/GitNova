package com.gitnova.service;

import com.gitnova.gitlet.GitletException;
import com.gitnova.gitlet.Utils;
import com.gitnova.mapper.BranchMapper;
import com.gitnova.mapper.CommitRecordMapper;
import com.gitnova.mapper.RepositoryMapper;
import com.gitnova.storage.ObjectStorage;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * 传输服务 — Phase 2/3 核心
 *
 * 负责：增量对象解包 + SHA-1 完整性校验 + CAS 指针更新
 */
@Service
public class TransferService {

    private final RepositoryMapper repositoryMapper;
    private final CommitRecordMapper commitRecordMapper;
    private final BranchMapper branchMapper;
    private final GitletService gitletService;
    private final ObjectStorage objectStorage;
    private final ApplicationEventPublisher eventPublisher;

    public TransferService(RepositoryMapper repositoryMapper,
                           CommitRecordMapper commitRecordMapper,
                           BranchMapper branchMapper,
                           GitletService gitletService,
                           ObjectStorage objectStorage,
                           ApplicationEventPublisher eventPublisher) {
        this.repositoryMapper = repositoryMapper;
        this.commitRecordMapper = commitRecordMapper;
        this.branchMapper = branchMapper;
        this.gitletService = gitletService;
        this.objectStorage = objectStorage;
        this.eventPublisher = eventPublisher;
    }

    /**
     * 处理接收到的对象包 — 解包 + SHA-1 校验 + 写入对象库
     *
     * ⚠️ 解包时对每个对象重新计算 SHA-1，与包头声明的 SHA-1 比对，
     * 不一致则拒绝整次 push，返回 400 + 具体错误对象。
     */
    public int unpackAndStore(String repoKey, byte[] objectsPack) {
        // TODO: Phase 2 — 解包逻辑
        // 1. 解析打包格式：[4 bytes N] + for each: [40 bytes SHA-1][8 bytes length][L bytes content]
        // 2. 对每个对象重新计算 SHA-1
        // 3. 与包头 SHA-1 比对，不一致则拒绝
        // 4. 写入 .gitlet/objects/
        if(objectsPack==null) throw new GitletException("传入空包");
        ByteBuffer buffer= ByteBuffer.wrap(objectsPack);
        int objectCount=buffer.getInt();
        if(objectCount==0) return 0;
        if(objectCount>10000) throw new GitletException("传入数量过多");
        for(int i=0;i<objectCount;i++){
            byte[] sha1Bytes =new byte[40];
            buffer.get(sha1Bytes);
            long length=buffer.getLong();
            if(length>500*1024*1024) throw new GitletException("对象大小异常，单文件不可超过500MB");
            byte[] contentsBytes=new byte[(int)length];
            buffer.get(contentsBytes);

            String declared = new String(sha1Bytes, StandardCharsets.UTF_8);
            String actual= Utils.sha1(contentsBytes);
            if(!actual.equals(declared)) throw new GitletException("SHA-1 校验失败！声明值: " + declared + "，实际计算值: " + actual);
        }
        buffer.rewind();
        buffer.getInt();
        for(int i=0;i<objectCount;i++){
            byte[] sha1Bytes=new byte[40];
            buffer.get(sha1Bytes);
            long length=buffer.getLong();
            byte[] contentsBytes=new byte[(int)length];
            buffer.get(contentsBytes);
            String declared=new String(sha1Bytes, StandardCharsets.UTF_8);
            objectStorage.writeObject(repoKey,declared,contentsBytes);
        }
        return objectCount;
    }

    /**
     * CAS 更新 HEAD 指针 — Phase 3 核心
     *
     * @param repoId        仓库 ID
     * @param baseHeadSha1  客户端认为的当前 HEAD（CAS 基准值）
     * @param newHeadSha1   新的 HEAD
     * @param branchName    分支名
     * @param commitMessage 提交信息
     * @param authorId      作者用户 ID
     */
    @Transactional
    public void updateHead(Long repoId, String baseHeadSha1, String newHeadSha1,
                           String branchName, String commitMessage, Long authorId) {
        // TODO: Phase 3 — CAS 并发控制
        // 1. CAS 更新 repository.head_commit_sha1
        //    UPDATE repository SET head_commit_sha1 = #{newHeadSha1}
        //    WHERE id = #{repoId} AND head_commit_sha1 = #{baseHeadSha1}
        // 2. affected rows == 0 → throw NonFastForwardException (409)
        // 3. 同步写入 commit_record 表
        // 4. 更新 branch 表 HEAD
        // 5. 发布 PostReceiveEvent（触发异步 Agent review）
        throw new UnsupportedOperationException("Phase 3: 待实现");
    }
}
