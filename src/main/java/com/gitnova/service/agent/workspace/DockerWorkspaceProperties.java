package com.gitnova.service.agent.workspace;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Server-owned execution policy. None of these values come from tool arguments. */
@ConfigurationProperties("gitnova.workspace.docker")
public record DockerWorkspaceProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("docker") String executable,
        @DefaultValue("gitnova-workspace:java21") String image,
        @DefaultValue("1000:1000") String user,
        @DefaultValue("1.0") double cpus,
        @DefaultValue("512") int memoryMb,
        @DefaultValue("128") int pidsLimit
) {
    public DockerWorkspaceProperties {
        if (executable == null || executable.isBlank() || image == null || image.isBlank()
                || image.startsWith("-") || user == null || !user.matches("[1-9][0-9]*:[1-9][0-9]*")
                || !Double.isFinite(cpus) || cpus <= 0 || memoryMb < 64 || pidsLimit < 16) {
            throw new IllegalArgumentException("Invalid Docker Workspace execution configuration");
        }
    }
}
