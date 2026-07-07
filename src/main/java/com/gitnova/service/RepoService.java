package com.gitnova.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.gitnova.common.UserContext;
import com.gitnova.dto.ApiResponse;
import com.gitnova.entity.RepoMember;
import com.gitnova.entity.Repository;
import com.gitnova.gitlet.Utils;
import com.gitnova.mapper.RepoMemberMapper;
import com.gitnova.mapper.RepositoryMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
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
    @Transactional(rollbackFor = Exception.class)
    public ApiResponse<?> createRepo(String name, String description, boolean isPrivate) {
        // TODO: Phase 1
        // 1. 校验仓库名唯一性（同一 owner 下）
        // 2. 写入 repository 表
        // 3. 写入 repo_member 表（owner 角色）
        // 4. 调用 gitletService.init(repoPath) 初始化对象库
        Long userId= UserContext.getUserId();
        if(userId==null) return  ApiResponse.error(401,"fail");

        if(name==null||name.isEmpty()) return ApiResponse.error(400,"仓库名不能为空");
        if(name.length()<3||name.length()>100) return ApiResponse.error(400,"仓库名长度应在 3~100 之间");
        for(char c:name.toCharArray()){
            if(!((c>='a'&&c<='z')||(c>='A'&&c<='Z')||(c>='0'&&c<='9')||c=='_'||c=='-'||c=='.')) return ApiResponse.error(400,"仓库名仅允许字母、数字、-、_、.");
        }
        if(description!=null&&description.length()>255) return ApiResponse.error(400,"描述不能超过 255 个字符");

        LambdaQueryWrapper<Repository>wrapper=new LambdaQueryWrapper<>();
        wrapper.eq(Repository::getOwnerId,userId).eq(Repository::getName,name);
        if(repositoryMapper.selectCount(wrapper)>0) return ApiResponse.error(400,"仓库已存在");

        Repository repo=new Repository();
        repo.setName(name);
        repo.setDescription(description);
        repo.setOwnerId(userId);
        repo.setIsPrivate(isPrivate?1:0);
        repo.setCreatedAt(LocalDateTime.now());
        repositoryMapper.insert(repo);

        RepoMember repoMember=new RepoMember();
        repoMember.setRepoId(repo.getId());
        repoMember.setUserId(userId);
        repoMember.setRole("owner");
        repoMemberMapper.insert(repoMember);

        String repoPath = Utils.join(String.valueOf(userId), String.valueOf(repo.getId())).getPath();
        gitletService.init(repoPath);

        return ApiResponse.success(Map.of(
                "id", repo.getId(),
                "name", repo.getName(),
                "ownerId", repo.getOwnerId(),
                "isPrivate", repo.getIsPrivate(),
                "createdAt", repo.getCreatedAt()
        ));
    }

    /**
     * 查询当前用户的仓库列表
     */
    public ApiResponse<?> listUserRepos() {
        // TODO: Phase 1
        Long userId=UserContext.getUserId();
        List<Repository> repoList=repoMemberMapper.selectByReposUserId(userId);
        return ApiResponse.success(repoList);
    }

    /**
     * 仓库详情
     */
    public ApiResponse<?> getRepoDetail(Long repoId) {
        // TODO: Phase 1
        Long userId=UserContext.getUserId();
        Repository repo=repositoryMapper.selectById(repoId);
        if(repo==null) return ApiResponse.error(404,"仓库不存在");

        if((repo.getIsPrivate()==1)){
            LambdaQueryWrapper<RepoMember>wrapper=new LambdaQueryWrapper<>();
            wrapper.eq(RepoMember::getRepoId,repo.getId())
                    .eq(RepoMember::getUserId,userId);
            RepoMember member=repoMemberMapper.selectOne(wrapper);
            if(member==null) return ApiResponse.error(403,"权限不足");
        }

        return ApiResponse.success(repo);
    }

    /**
     * 删除仓库（仅 owner）
     */
    public ApiResponse<?> deleteRepo(Long repoId) {
        // TODO: Phase 1
        throw new UnsupportedOperationException("Phase 1: 待实现");
    }
}
