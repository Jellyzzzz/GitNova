package com.gitnova.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gitnova.common.UserContext;
import com.gitnova.dto.ApiResponse;
import com.gitnova.dto.NegotiationResponse;
import com.gitnova.dto.PushRequest;
import com.gitnova.dto.TransferMetadata;
import com.gitnova.entity.Repository;
import com.gitnova.service.ObjectNegotiationService;
import com.gitnova.service.RepositoryAccessService;
import com.gitnova.service.TransferService;
import com.gitnova.storage.RepoKey;
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
    private final RepositoryAccessService repositoryAccessService;
    private final ObjectMapper objectMapper;

    public TransferController(
            ObjectNegotiationService negotiationService,
            TransferService transferService,
            RepositoryAccessService repositoryAccessService,
            ObjectMapper objectMapper
    ) {
        this.negotiationService = negotiationService;
        this.transferService = transferService;
        this.repositoryAccessService = repositoryAccessService;
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
        long actorId = UserContext.getUserId();
        Repository repository = repositoryAccessService.requireWriteAccess(
                repoId,
                actorId
        );
        String repoKey = RepoKey.of(
                repository.getOwnerId(),
                repository.getId()
        ).value();
        NegotiationResponse result = negotiationService.negotiate(
                repoId,
                repoKey,
                request
        );
        return ApiResponse.success(result);
    }

    /**
     * Step 2 — 传输（Transfer）
     *
     * 客户端上传打包的 missingObjects，服务端解包校验后 CAS 更新 HEAD。
     * multipart：metadata（JSON）+ objects（二进制文件）
     */
    @PostMapping("/transfer")
    public ApiResponse<?> transfer(
            @PathVariable Long repoId,
            @RequestParam("metadata") String metadataJson,
            @RequestParam("objects") MultipartFile objectsFile
    ) throws Exception {
        long actorId = UserContext.getUserId();
        Repository repository = repositoryAccessService.requireWriteAccess(
                repoId,
                actorId
        );
        TransferMetadata metadata = objectMapper.readValue(
                metadataJson,
                TransferMetadata.class
        );
        String repoKey = RepoKey.of(
                repository.getOwnerId(),
                repository.getId()
        ).value();
        int count = transferService.unpackAndStore(
                repoKey,
                objectsFile.getInputStream(),
                objectsFile.getSize()
        );
        transferService.updateHead(
                repoId,
                repoKey,
                metadata.getBaseHeadSha1(),
                metadata.getNewHeadSha1(),
                metadata.getBranchName(),
                actorId,
                metadata.isReview()
        );
        return ApiResponse.success(Map.of(
                "newHeadSha1",
                metadata.getNewHeadSha1(),
                "objectsStored",
                count
        ));
    }
}
