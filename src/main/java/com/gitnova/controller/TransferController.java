package com.gitnova.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gitnova.common.UserContext;
import com.gitnova.dto.ApiResponse;
import com.gitnova.dto.NegotiationResponse;
import com.gitnova.dto.PushRequest;
import com.gitnova.dto.TransferMetadata;
import com.gitnova.entity.RepoMember;
import com.gitnova.entity.Repository;
import com.gitnova.gitlet.Utils;
import com.gitnova.mapper.RepoMemberMapper;
import com.gitnova.mapper.RepositoryMapper;
import com.gitnova.service.ObjectNegotiationService;
import com.gitnova.service.TransferService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * 对象传输接口 — Phase 2/3 核心
 *
 * 简化版 Git Smart Protocol：两步 HTTP 交互完成 push
 */
@RestController
@RequestMapping("/api/repos/{repoId}/push")
public class TransferController {

    private final ObjectNegotiationService negotiationService;
    private final TransferService transferService;
    private final RepositoryMapper repositoryMapper;
    private final RepoMemberMapper repoMemberMapper;
    private final ObjectMapper objectMapper;

    public TransferController(ObjectNegotiationService negotiationService,
                              TransferService transferService,
                              RepositoryMapper repositoryMapper,
                              RepoMemberMapper repoMemberMapper, ObjectMapper objectMapper) {
        this.negotiationService = negotiationService;
        this.transferService = transferService;
        this.repositoryMapper = repositoryMapper;
        this.repoMemberMapper = repoMemberMapper;
        this.objectMapper = objectMapper;
    }

    /**
     * Step 1 — 协商（Negotiation）
     *
     * 客户端上报本地 HEAD + 所有对象 SHA-1 列表，
     * 服务端返回缺失的对象列表。
     */
    @PostMapping("/negotiate")
    public ApiResponse<NegotiationResponse> negotiate(@PathVariable Long repoId,
                                                        @RequestBody PushRequest request) {
        // TODO: Phase 2
        // 1. 校验 repoId 存在 + 当前用户是仓库成员（用 repositoryMapper / repoMemberMapper）
        // 2. 拼接 repoKey = ownerId + "/" + repoId
        // 3. NegotiationResponse result = negotiationService.negotiate(repoKey, request);
        // 4. return ApiResponse.success(result);
        long userId= UserContext.getUserId();
        Repository repo=repositoryMapper.selectById(repoId);
        if(repo==null) return ApiResponse.error(404,"仓库不存在");
        LambdaQueryWrapper<RepoMember>wrapper=new LambdaQueryWrapper<>();
        wrapper.eq(RepoMember::getUserId,userId).eq(RepoMember::getRepoId,repoId);
        RepoMember member=repoMemberMapper.selectOne(wrapper);
        if(member==null) return ApiResponse.error(403,"无权访问该仓库");
        String repoKey= Utils.join(String.valueOf(repo.getOwnerId()),String.valueOf(repoId)).getPath();
        NegotiationResponse res=negotiationService.negotiate(repoKey,request);
        return ApiResponse.success(res);
    }

    /**
     * Step 2 — 传输（Transfer）
     *
     * 客户端上传打包的 missingObjects，服务端解包校验后 CAS 更新 HEAD。
     * multipart：metadata（JSON）+ objects（二进制文件）
     */
    @PostMapping("/transfer")
    public ApiResponse<?> transfer(@PathVariable Long repoId,
                                   @RequestParam("metadata") String metadataJson,
                                   @RequestParam("objects") MultipartFile objectsFile){
        // TODO: Phase 2/3
        // 1. 校验 repoId 存在 + 当前用户是仓库成员
        // 2. 解析 metadata → TransferMetadata meta
        // 3. 拼接 repoKey = ownerId + "/" + repoId
        // 4. int count = transferService.unpackAndStore(repoKey, objectsFile.getBytes())
        // 5. transferService.updateHead(repoId, meta.getBaseHeadSha1(), meta.getNewHeadSha1(), ...)
        // 6. return ApiResponse.success(Map.of("newHeadSha1", ..., "objectsStored", count))
        try {
            long userId = UserContext.getUserId();
            Repository repo = repositoryMapper.selectById(repoId);
            if (repo == null) return ApiResponse.error(404, "仓库不存在");
            LambdaQueryWrapper<RepoMember> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(RepoMember::getRepoId, repoId).eq(RepoMember::getUserId, userId);
            RepoMember member = repoMemberMapper.selectOne(wrapper);
            if (member == null) return ApiResponse.error(403, "用户权限不足");
            TransferMetadata metadata = objectMapper.readValue(metadataJson, TransferMetadata.class);
            String repoKey=Utils.join(String.valueOf(repo.getOwnerId()),String.valueOf(repoId)).getPath();
        }catch(Exception e){
            e.printStackTrace();
            return ApiResponse.error(500, "传输解析失败: " + e.getMessage());
        };
        throw new UnsupportedOperationException("Phase 2/3: 待实现");
    }
}
