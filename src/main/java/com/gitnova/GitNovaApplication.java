package com.gitnova;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * GitNova — 轻量级私有代码托管与智能审查平台
 *
 * 核心引擎：Gitlet（自实现版本控制）
 * Web 框架：Spring Boot 3.x
 * 并发控制：CAS 乐观锁
 */
@SpringBootApplication
@EnableAsync
public class GitNovaApplication {

    public static void main(String[] args) {
        SpringApplication.run(GitNovaApplication.class, args);
    }
}
