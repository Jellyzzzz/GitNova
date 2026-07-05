package com.gitnova.controller;

import com.gitnova.dto.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 提交记录查询接口
 */
@RestController
@RequestMapping("/api/repos/{repoId}/commits")
public class CommitController {

    /**
     * 查询 commit 历史列表
     *
     * @param repoId     仓库 ID
     * @param branchName 分支名（可选，默认 main）
     * @param page       页码
     * @param size       每页大小
     */
    @GetMapping
    public ApiResponse<?> listCommits(@PathVariable Long repoId,
                                      @RequestParam(defaultValue = "main") String branchName,
                                      @RequestParam(defaultValue = "1") int page,
                                      @RequestParam(defaultValue = "20") int size) {
        // TODO: Phase 1 — 从 commit_record 表查询 commit 历史
        throw new UnsupportedOperationException("Phase 1: 待实现");
    }

    /**
     * 查询单个 commit 详情
     */
    @GetMapping("/{sha1}")
    public ApiResponse<?> getCommit(@PathVariable Long repoId,
                                    @PathVariable String sha1) {
        // TODO: Phase 1
        throw new UnsupportedOperationException("Phase 1: 待实现");
    }
}
