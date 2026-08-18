package com.gitnova.service;

import com.gitnova.dto.NegotiationResponse;
import com.gitnova.dto.PushRequest;
import com.gitnova.gitobject.GitObjectId;
import com.gitnova.mapper.BranchMapper;
import com.gitnova.storage.ObjectStorage;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 对象协商服务 — Phase 2 核心
 *
 * 简化版 Git Smart Protocol：客户端单次全量上报 SHA-1 列表（非文件内容，只是哈希字符串），
 * 服务端遍历检查每个对象是否存在，返回缺失列表。一次 RTT 完成协商。
 *
 * 💡 Design Note — 为什么不用 have/want 多轮交互？
 * 单次全量上报 SHA-1 列表开销极小（每个 SHA-1 仅 40 字节），
 * 一次 RTT 完成协商更简单可控，面试时也更好解释。
 */
@Service
public class ObjectNegotiationService {

    private final BranchMapper branchMapper;
    private final ObjectStorage objectStorage;
    public ObjectNegotiationService(BranchMapper branchMapper, ObjectStorage objectStorage) {
        this.branchMapper = branchMapper;
        this.objectStorage = objectStorage;
    }

    /**
     * 执行对象协商
     *
     * @param repoKey 仓库路径
     * @param request  客户端上报的 HEAD 和对象列表
     * @return { "remoteHeadSha1": "...", "missingObjects": [...] }
     */
    public NegotiationResponse negotiate(Long repoId, String repoKey, PushRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("push request must not be null");
        }
        String branchName = BranchName.requireValid(request.getBranchName() == null ? "main" : request.getBranchName());
        String remoteHead = branchMapper.findHead(repoId, branchName);
        if (request.getLocalObjects() == null || request.getLocalObjects().isEmpty()) {
            return new NegotiationResponse(remoteHead, List.of());
        }
        List<String> missing = new ArrayList<>();
        for (String sha1 : request.getLocalObjects()) {
            String objectId = GitObjectId.of(sha1).value();
            if (!objectStorage.existsObject(repoKey, objectId)) {
                missing.add(sha1);
            }
        }
        return new NegotiationResponse(remoteHead, missing);
    }
}
