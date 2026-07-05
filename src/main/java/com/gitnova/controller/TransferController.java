package com.gitnova.controller;

import com.gitnova.dto.ApiResponse;
import com.gitnova.dto.PushRequest;
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

    public TransferController(ObjectNegotiationService negotiationService,
                              TransferService transferService) {
        this.negotiationService = negotiationService;
        this.transferService = transferService;
    }

    /**
     * Step 1 — 协商（Negotiation）
     *
     * 客户端上报本地 HEAD + 所有对象 SHA-1 列表，
     * 服务端返回缺失的对象列表。
     */
    @PostMapping("/negotiate")
    public ApiResponse<Map<String, Object>> negotiate(@PathVariable Long repoId,
                                                       @RequestBody PushRequest request) {
        // TODO: Phase 2
        // String repoPath = getRepoPath(repoId);
        // Map<String, Object> result = negotiationService.negotiate(repoPath, request);
        // return ApiResponse.success(result);
        throw new UnsupportedOperationException("Phase 2: 待实现");
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
                                   @RequestParam("objects") MultipartFile objectsFile) {
        // TODO: Phase 2/3
        // 1. 解析 metadata：newHeadSha1, baseHeadSha1, branchName, commitMessage
        // 2. 调用 transferService.unpackAndStore(repoPath, objectsFile.getBytes())
        // 3. 调用 transferService.updateHead(repoId, baseHeadSha1, newHeadSha1, ...)
        // 4. 返回成功
        throw new UnsupportedOperationException("Phase 2/3: 待实现");
    }
}
