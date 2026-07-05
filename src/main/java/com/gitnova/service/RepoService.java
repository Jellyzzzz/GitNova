package com.gitnova.service;

import com.gitnova.dto.ApiResponse;
import com.gitnova.mapper.RepoMemberMapper;
import com.gitnova.mapper.RepositoryMapper;
import org.springframework.stereotype.Service;

/**
 * 仓库服务 — CRUD + 权限校验
 */
@Service
public class RepoService {

    private final RepositoryMapper repositoryMapper;
    private final RepoMemberMapper repoMemberMapper;
    private final GitletService gitletService;

    public RepoService(RepositoryMapper repositoryMapper,
                       RepoMemberMapper repoMemberMapper,
                       GitletService gitletService) {
        this.repositoryMapper = repositoryMapper;
        this.repoMemberMapper = repoMemberMapper;
        this.gitletService = gitletService;
    }

    /**
     * 创建仓库（内部调用 GitletService.init()）
     */
    public ApiResponse<?> createRepo(String name, String description, boolean isPrivate) {
        // TODO: Phase 1
        // 1. 校验仓库名唯一性（同一 owner 下）
        // 2. 写入 repository 表
        // 3. 写入 repo_member 表（owner 角色）
        // 4. 调用 gitletService.init(repoPath) 初始化对象库
        throw new UnsupportedOperationException("Phase 1: 待实现");
    }

    /**
     * 查询当前用户的仓库列表
     */
    public ApiResponse<?> listUserRepos() {
        // TODO: Phase 1
        throw new UnsupportedOperationException("Phase 1: 待实现");
    }

    /**
     * 仓库详情
     */
    public ApiResponse<?> getRepoDetail(Long repoId) {
        // TODO: Phase 1
        throw new UnsupportedOperationException("Phase 1: 待实现");
    }

    /**
     * 删除仓库（仅 owner）
     */
    public ApiResponse<?> deleteRepo(Long repoId) {
        // TODO: Phase 1
        throw new UnsupportedOperationException("Phase 1: 待实现");
    }
}
