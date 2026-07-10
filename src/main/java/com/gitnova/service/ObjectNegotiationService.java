package com.gitnova.service;

import com.gitnova.dto.PushRequest;
import com.gitnova.storage.ObjectStorage;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

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

    private final GitletService gitletService;
    private final ObjectStorage objectStorage;
    public ObjectNegotiationService(GitletService gitletService,ObjectStorage objectStorage) {
        this.objectStorage=objectStorage;
        this.gitletService = gitletService;
    }

    /**
     * 执行对象协商
     *
     * @param repoPath 仓库路径
     * @param request  客户端上报的 HEAD 和对象列表
     * @return { "remoteHeadSha1": "...", "missingObjects": [...] }
     */
    public Map<String, Object> negotiate(String repoPath, PushRequest request) {
        // TODO: Phase 2 — 协商逻辑
        // 1. 获取服务端当前 HEAD
        // 2. 遍历 request.getLocalObjects()，检查每个 SHA-1 在服务端是否存在
        // 3. 返回 remoteHeadSha1 + missingObjects
        throw new UnsupportedOperationException("Phase 2: 待实现");
    }
}
