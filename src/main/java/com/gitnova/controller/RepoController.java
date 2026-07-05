package com.gitnova.controller;

import com.gitnova.dto.ApiResponse;
import com.gitnova.service.RepoService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 仓库 CRUD 接口
 */
@RestController
@RequestMapping("/api/repos")
public class RepoController {

    private final RepoService repoService;

    public RepoController(RepoService repoService) {
        this.repoService = repoService;
    }

    /**
     * 创建仓库（内部调用 GitletService.init()）
     */
    @PostMapping
    public ApiResponse<?> createRepo(@RequestParam String name,
                                     @RequestParam(required = false) String description,
                                     @RequestParam(defaultValue = "true") boolean isPrivate) {
        return repoService.createRepo(name, description, isPrivate);
    }

    /**
     * 查询当前用户的仓库列表
     */
    @GetMapping
    public ApiResponse<?> listRepos() {
        return repoService.listUserRepos();
    }

    /**
     * 仓库详情
     */
    @GetMapping("/{repoId}")
    public ApiResponse<?> getRepo(@PathVariable Long repoId) {
        return repoService.getRepoDetail(repoId);
    }

    /**
     * 删除仓库（仅 owner）
     */
    @DeleteMapping("/{repoId}")
    public ApiResponse<?> deleteRepo(@PathVariable Long repoId) {
        return repoService.deleteRepo(repoId);
    }
}
