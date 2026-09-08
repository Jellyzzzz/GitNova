package com.gitnova.service.session;

import com.gitnova.mapper.BranchMapper;
import com.gitnova.service.BranchName;
import com.gitnova.service.agent.workspace.SnapshotScope;
import org.springframework.stereotype.Service;

import java.util.Objects;

/** Resolves a repository-scoped branch selector to one immutable snapshot. */
@Service
public final class RepositoryRevisionService {

    private final BranchMapper branchMapper;

    public RepositoryRevisionService(BranchMapper branchMapper) {
        this.branchMapper = Objects.requireNonNull(
                branchMapper,
                "branchMapper must not be null"
        );
    }

    public SnapshotScope requireBranchSnapshot(Long repoId, String branchName) {
        if (repoId == null || repoId <= 0) {
            throw new IllegalArgumentException("repoId must be positive");
        }
        String branch = BranchName.requireValid(branchName == null ? "main" : branchName);
        String head = branchMapper.findHead(repoId, branch);
        if (head == null) {
            throw new BranchNotFoundException(branch);
        }
        try {
            return SnapshotScope.of(head);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "Repository branch points to an invalid Commit identity",
                    exception
            );
        }
    }

    public static final class BranchNotFoundException extends RuntimeException {
        private final String branchName;

        private BranchNotFoundException(String branchName) {
            super("Repository branch does not exist: " + branchName);
            this.branchName = branchName;
        }

        public String branchName() {
            return branchName;
        }
    }
}
