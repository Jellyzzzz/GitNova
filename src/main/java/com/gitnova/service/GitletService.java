package com.gitnova.service;

import com.gitnova.gitlet.Repository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;

/**
 * Gitlet 核心引擎包装服务
 *
 * 💡 不修改 Gitlet 原有逻辑，只做包装。
 * 每个仓库对应一个独立的工作目录：{basePath}/{ownerId}/{repoName}/
 * .gitlet/ 目录自动创建在该工作目录内。
 *
 * ⚠️ Warning — 路径隔离：每个仓库必须有独立工作目录，否则 .gitlet 会互相污染。
 */
@Service
public class GitletService {

    @Value("${gitnova.repo.base-path:./gitnova-repos}")
    private String basePath;

    /**
     * 初始化仓库 — 在磁盘上创建 .gitlet 目录结构
     *
     * @param repoPath 仓库路径 key = "{ownerId}/{repoName}"
     */
    public void init(String repoPath) {
        String fullPath = basePath + File.separator + repoPath;
        Repository repo = new Repository(fullPath);
        repo.init();
    }

    /**
     * 提交变更 — 创建新的 Commit 对象并返回 SHA-1
     *
     * @param repoPath 仓库路径 key = "{ownerId}/{repoName}"
     * @param message  提交信息
     * @return 新 commit 的 SHA-1
     */
    public String commit(String repoPath, String message) {
        String fullPath = basePath + File.separator + repoPath;
        Repository repo = new Repository(fullPath);
        return repo.commit(message);
    }

    /**
     * 获取仓库实例（用于复杂操作）
     *
     * @param repoPath 仓库路径 key = "{ownerId}/{repoName}"
     * @return Repository 实例
     */
    public Repository getRepository(String repoPath) {
        String fullPath = basePath + File.separator + repoPath;
        return new Repository(fullPath);
    }

    /**
     * 获取仓库当前 HEAD commit SHA-1
     */
    public String getHeadSha1(String repoPath) {
        String fullPath = basePath + File.separator + repoPath;
        Repository repo = new Repository(fullPath);
        return repo.getHeadSha1();
    }

    /**
     * 检查对象是否存在
     *
     * @param repoPath 仓库路径
     * @param sha1     对象 SHA-1
     * @return 是否存在
     */
    public boolean objectExists(String repoPath, String sha1) {
        String fullPath = basePath + File.separator + repoPath;
        Repository repo = new Repository(fullPath);
        return repo.blobExists(sha1) || repo.commitExists(sha1);
    }

    /**
     * 获取指定 commit 的 diff
     *
     * @param repoPath  仓库路径
     * @param commitSha1 commit SHA-1
     * @return diff 文本
     */
    public String getDiff(String repoPath, String commitSha1) {
        // TODO: Phase 4 — 获取 commit 的变更内容
        throw new UnsupportedOperationException("Phase 4: 待实现");
    }
}
