# SQL列与Java映射（由唯一目标SQL生成）

新表和Session增量仅来自 `../schema/target.sql.reference`。Session既有标记列必须H0对照实际库；不是完整建库脚本。UTC LocalDateTime只用于SQL行，API/协议使用Instant。POJO不是可直接暴露给用户的DTO。

## agent_session
Java：`com.gitnova.entity.agent.control.ControlRows.SessionRow`

| SQL列 | 类型 | Java字段 | nullable/生成 |
|---|---|---|---|
| session_id | CHAR(36) | `String sessionId` | 必需 |
| creation_idempotency_key | VARCHAR(128) | `String creationIdempotencyKey` | 必需 |
| created_by_actor_id | BIGINT | `Long createdByActorId` | 必需 |
| repo_id | BIGINT | `Long repoId` | 必需 |
| repo_key | VARCHAR(128) | `String repoKey` | 必需 |
| status | VARCHAR(24) | `String status` | 必需 |
| version | BIGINT | `Long version` | 必需 |
| created_at | DATETIME(6) | `LocalDateTime createdAt` | 必需 |
| updated_at | DATETIME(6) | `LocalDateTime updatedAt` | 必需 |
| closed_at | DATETIME(6) | `LocalDateTime closedAt` | 可空 |
| execution_backend | VARCHAR(24) | `String executionBackend` | 必需 |
| active_task_id | CHAR(36) | `String activeTaskId` | 可空 |
| active_workline_id | CHAR(36) | `String activeWorklineId` | 可空 |
| session_version | BIGINT | `Long sessionVersion` | 必需 |
| sync_operation_id | CHAR(36) | `String syncOperationId` | 可空 |
| next_runner_epoch | BIGINT | `Long nextRunnerEpoch` | 必需 |
| current_runner_epoch | BIGINT | `Long currentRunnerEpoch` | 必需 |
| workflow_state | VARCHAR(16) | `String workflowState` | 必需 |
| creation_request_digest | CHAR(64) | `String creationRequestDigest` | 可空 |

## agent_sandbox_binding
Java：`com.gitnova.entity.agent.control.ControlRows.BindingRow`

| SQL列 | 类型 | Java字段 | nullable/生成 |
|---|---|---|---|
| session_id | CHAR(36) | `String sessionId` | 必需 |
| workline_id | CHAR(36) | `String worklineId` | 必需 |
| runner_epoch | BIGINT | `Long runnerEpoch` | 必需 |
| bootstrap_id | CHAR(36) | `String bootstrapId` | 可空 |
| bootstrap_sha256 | CHAR(64) | `String bootstrapSha256` | 可空 |
| create_operation_id | CHAR(36) | `String createOperationId` | 必需 |
| sandbox_id | VARCHAR(128) | `String sandboxId` | 可空 |
| volume_name | VARCHAR(128) | `String volumeName` | 必需 |
| status | VARCHAR(24) | `String status` | 必需 |
| token_salt | CHAR(32) | `String tokenSalt` | 必需 |
| image_ref | VARCHAR(512) | `String imageRef` | 必需 |
| runtime_config_json | JSON | `String runtimeConfigJson` | 必需 |
| runtime_config_digest | CHAR(64) | `String runtimeConfigDigest` | 必需 |
| last_archived_sequence | BIGINT | `Long lastArchivedSequence` | 必需 |
| expires_at | DATETIME(6) | `LocalDateTime expiresAt` | 可空 |
| last_observed_at | DATETIME(6) | `LocalDateTime lastObservedAt` | 可空 |
| last_used_at | DATETIME(6) | `LocalDateTime lastUsedAt` | 必需 |
| operation_owner | VARCHAR(128) | `String operationOwner` | 可空 |
| operation_until | DATETIME(6) | `LocalDateTime operationUntil` | 可空 |
| version | BIGINT | `Long version` | 必需 |
| last_error_code | VARCHAR(64) | `String lastErrorCode` | 可空 |
| created_at | DATETIME(6) | `LocalDateTime createdAt` | 必需 |
| updated_at | DATETIME(6) | `LocalDateTime updatedAt` | 必需 |

## agent_control_task
Java：`com.gitnova.entity.agent.control.ControlRows.TaskRow`

| SQL列 | 类型 | Java字段 | nullable/生成 |
|---|---|---|---|
| task_id | CHAR(36) | `String taskId` | 必需 |
| session_id | CHAR(36) | `String sessionId` | 必需 |
| workline_id | CHAR(36) | `String worklineId` | 必需 |
| idempotency_key | VARCHAR(128) | `String idempotencyKey` | 必需 |
| actor_id | BIGINT | `Long actorId` | 必需 |
| message | LONGTEXT | `String message` | 必需 |
| request_digest | CHAR(64) | `String requestDigest` | 必需 |
| status | VARCHAR(24) | `String status` | 必需 |
| current_attempt_id | CHAR(36) | `String currentAttemptId` | 可空 |
| attempt_number | BIGINT | `Long attemptNumber` | 必需 |
| answer_event_id | VARCHAR(160) | `String answerEventId` | 可空 |
| terminal_reason | VARCHAR(64) | `String terminalReason` | 可空 |
| cancel_requested | TINYINT(1) | `Integer cancelRequested` | 必需 |
| version | BIGINT | `Long version` | 必需 |
| created_at | DATETIME(6) | `LocalDateTime createdAt` | 必需 |
| updated_at | DATETIME(6) | `LocalDateTime updatedAt` | 必需 |
| finished_at | DATETIME(6) | `LocalDateTime finishedAt` | 可空 |

## agent_control_attempt
Java：`com.gitnova.entity.agent.control.ControlRows.AttemptRow`

| SQL列 | 类型 | Java字段 | nullable/生成 |
|---|---|---|---|
| attempt_id | CHAR(36) | `String attemptId` | 必需 |
| task_id | CHAR(36) | `String taskId` | 必需 |
| session_id | CHAR(36) | `String sessionId` | 必需 |
| workline_id | CHAR(36) | `String worklineId` | 必需 |
| attempt_number | BIGINT | `Long attemptNumber` | 必需 |
| runner_epoch | BIGINT | `Long runnerEpoch` | 必需 |
| status | VARCHAR(24) | `String status` | 必需 |
| expected_published_head | CHAR(40) | `String expectedPublishedHead` | 必需 |
| deadline_at | DATETIME(6) | `LocalDateTime deadlineAt` | 必需 |
| config_digest | CHAR(64) | `String configDigest` | 必需 |
| answer_event_id | VARCHAR(160) | `String answerEventId` | 可空 |
| terminal_reason | VARCHAR(64) | `String terminalReason` | 可空 |
| created_at | DATETIME(6) | `LocalDateTime createdAt` | 必需 |
| finished_at | DATETIME(6) | `LocalDateTime finishedAt` | 可空 |

## agent_control_command
Java：`com.gitnova.entity.agent.control.ControlRows.CommandRow`

| SQL列 | 类型 | Java字段 | nullable/生成 |
|---|---|---|---|
| command_id | CHAR(36) | `String commandId` | 必需 |
| session_id | CHAR(36) | `String sessionId` | 必需 |
| workline_id | CHAR(36) | `String worklineId` | 必需 |
| runner_epoch | BIGINT | `Long runnerEpoch` | 必需 |
| task_id | CHAR(36) | `String taskId` | 可空 |
| attempt_id | CHAR(36) | `String attemptId` | 可空 |
| type | VARCHAR(32) | `String type` | 必需 |
| envelope_json | LONGTEXT | `String envelopeJson` | 必需 |
| envelope_digest | CHAR(64) | `String envelopeDigest` | 必需 |
| status | VARCHAR(24) | `String status` | 必需 |
| receipt_json | JSON | `String receiptJson` | 可空 |
| send_count | INT | `Integer sendCount` | 必需 |
| next_attempt_at | DATETIME(6) | `LocalDateTime nextAttemptAt` | 必需 |
| last_error_code | VARCHAR(64) | `String lastErrorCode` | 可空 |
| created_at | DATETIME(6) | `LocalDateTime createdAt` | 必需 |
| updated_at | DATETIME(6) | `LocalDateTime updatedAt` | 必需 |

## agent_event_archive
Java：`com.gitnova.entity.agent.control.ControlRows.EventRow`

| SQL列 | 类型 | Java字段 | nullable/生成 |
|---|---|---|---|
| archive_offset | BIGINT UNSIGNED | `Long archiveOffset` | 必需 |
| event_id | VARCHAR(160) | `String eventId` | 必需 |
| session_id | CHAR(36) | `String sessionId` | 必需 |
| workline_id | CHAR(36) | `String worklineId` | 必需 |
| runner_epoch | BIGINT | `Long runnerEpoch` | 必需 |
| sequence | BIGINT | `Long sequence` | 必需 |
| task_id | CHAR(36) | `String taskId` | 可空 |
| attempt_id | CHAR(36) | `String attemptId` | 可空 |
| type | VARCHAR(40) | `String type` | 必需 |
| event_json | LONGTEXT | `String eventJson` | 必需 |
| event_digest | CHAR(64) | `String eventDigest` | 必需 |
| occurred_at | DATETIME(6) | `LocalDateTime occurredAt` | 必需 |
| archived_at | DATETIME(6) | `LocalDateTime archivedAt` | 必需 |

## agent_snapshot_archive
Java：`com.gitnova.entity.agent.control.ControlRows.ArchiveRow`

| SQL列 | 类型 | Java字段 | nullable/生成 |
|---|---|---|---|
| archive_id | CHAR(36) | `String archiveId` | 必需 |
| session_id | CHAR(36) | `String sessionId` | 必需 |
| workline_id | CHAR(36) | `String worklineId` | 必需 |
| export_id | CHAR(36) | `String exportId` | 必需 |
| runner_epoch | BIGINT | `Long runnerEpoch` | 必需 |
| task_id | CHAR(36) | `String taskId` | 可空 |
| attempt_id | CHAR(36) | `String attemptId` | 可空 |
| through_sequence | BIGINT | `Long throughSequence` | 必需 |
| tree_digest | CHAR(64) | `String treeDigest` | 必需 |
| state_digest | CHAR(64) | `String stateDigest` | 可空 |
| bundle_sha256 | CHAR(64) | `String bundleSha256` | 必需 |
| bundle_bytes | BIGINT | `Long bundleBytes` | 必需 |
| storage_key | VARCHAR(512) | `String storageKey` | 必需 |
| manifest_json | JSON | `String manifestJson` | 必需 |
| execution_outcome | VARCHAR(24) | `String executionOutcome` | 必需 |
| purpose | VARCHAR(16) | `String purpose` | 必需 |
| status | VARCHAR(24) | `String status` | 必需 |
| confirmed_published_head | CHAR(40) | `String confirmedPublishedHead` | 必需 |
| checkpoint_command_id | CHAR(36) | `String checkpointCommandId` | 可空 |
| created_at | DATETIME(6) | `LocalDateTime createdAt` | 必需 |
| updated_at | DATETIME(6) | `LocalDateTime updatedAt` | 必需 |

## agent_platform_operation
Java：`com.gitnova.entity.agent.control.ControlRows.PlatformOperationRow`

| SQL列 | 类型 | Java字段 | nullable/生成 |
|---|---|---|---|
| operation_id | CHAR(36) | `String operationId` | 必需 |
| session_id | CHAR(36) | `String sessionId` | 必需 |
| workline_id | CHAR(36) | `String worklineId` | 必需 |
| task_id | CHAR(36) | `String taskId` | 必需 |
| attempt_id | CHAR(36) | `String attemptId` | 必需 |
| runner_epoch | BIGINT | `Long runnerEpoch` | 必需 |
| tool_call_id | VARCHAR(256) | `String toolCallId` | 必需 |
| type | VARCHAR(32) | `String type` | 必需 |
| request_json | LONGTEXT | `String requestJson` | 必需 |
| request_digest | CHAR(64) | `String requestDigest` | 必需 |
| status | VARCHAR(24) | `String status` | 必需 |
| result_json | JSON | `String resultJson` | 可空 |
| error_code | VARCHAR(64) | `String errorCode` | 可空 |
| attempt_count | INT | `Integer attemptCount` | 必需 |
| next_attempt_at | DATETIME(6) | `LocalDateTime nextAttemptAt` | 必需 |
| created_at | DATETIME(6) | `LocalDateTime createdAt` | 必需 |
| updated_at | DATETIME(6) | `LocalDateTime updatedAt` | 必需 |

## agent_publication
Java：`com.gitnova.entity.agent.control.ControlRows.PublicationRow`

| SQL列 | 类型 | Java字段 | nullable/生成 |
|---|---|---|---|
| operation_id | CHAR(36) | `String operationId` | 必需 |
| publication_id | CHAR(36) | `String publicationId` | 必需 |
| session_id | CHAR(36) | `String sessionId` | 必需 |
| workline_id | CHAR(36) | `String worklineId` | 必需 |
| export_id | CHAR(36) | `String exportId` | 必需 |
| archive_id | CHAR(36) | `String archiveId` | 必需 |
| task_id | CHAR(36) | `String taskId` | 可空 |
| branch_id | BIGINT | `Long branchId` | 必需 |
| branch_name | VARCHAR(100) | `String branchName` | 必需 |
| expected_head | CHAR(40) | `String expectedHead` | 必需 |
| tree_digest | CHAR(64) | `String treeDigest` | 必需 |
| message | TEXT | `String message` | 必需 |
| author_id | BIGINT | `Long authorId` | 必需 |
| commit_epoch_millis | BIGINT | `Long commitEpochMillis` | 必需 |
| result_commit | CHAR(40) | `String resultCommit` | 可空 |
| status | VARCHAR(24) | `String status` | 必需 |
| error_code | VARCHAR(64) | `String errorCode` | 可空 |
| version | BIGINT | `Long version` | 必需 |
| created_at | DATETIME(6) | `LocalDateTime createdAt` | 必需 |
| updated_at | DATETIME(6) | `LocalDateTime updatedAt` | 必需 |

## agent_workline
Java：`com.gitnova.entity.agent.control.WorklineRows.WorklineRow`

| SQL列 | 类型 | Java字段 | nullable/生成 |
|---|---|---|---|
| workline_id | CHAR(36) | `String worklineId` | 必需 |
| session_id | CHAR(36) | `String sessionId` | 必需 |
| ordinal | BIGINT | `Long ordinal` | 必需 |
| repo_id | BIGINT | `Long repoId` | 必需 |
| base_commit | CHAR(40) | `String baseCommit` | 必需 |
| published_head | CHAR(40) | `String publishedHead` | 必需 |
| source_branch_id | BIGINT | `Long sourceBranchId` | 可空 |
| source_branch_name | VARCHAR(100) | `String sourceBranchName` | 必需 |
| target_branch_id | BIGINT | `Long targetBranchId` | 可空 |
| target_branch_name | VARCHAR(100) | `String targetBranchName` | 必需 |
| status | VARCHAR(24) | `String status` | 必需 |
| latest_recovery_archive_id | CHAR(36) | `String latestRecoveryArchiveId` | 可空 |
| merged_pr_id | BIGINT | `Long mergedPrId` | 可空 |
| version | BIGINT | `Long version` | 必需 |
| created_at | DATETIME(6) | `LocalDateTime createdAt` | 必需 |
| updated_at | DATETIME(6) | `LocalDateTime updatedAt` | 必需 |

## agent_sync_operation
Java：`com.gitnova.entity.agent.control.WorklineRows.SyncRow`

| SQL列 | 类型 | Java字段 | nullable/生成 |
|---|---|---|---|
| sync_id | CHAR(36) | `String syncId` | 必需 |
| session_id | CHAR(36) | `String sessionId` | 必需 |
| actor_id | BIGINT | `Long actorId` | 必需 |
| request_key | VARCHAR(128) | `String requestKey` | 必需 |
| request_digest | CHAR(64) | `String requestDigest` | 必需 |
| old_workline_id | CHAR(36) | `String oldWorklineId` | 必需 |
| expected_session_version | BIGINT | `Long expectedSessionVersion` | 必需 |
| target_branch_id | BIGINT | `Long targetBranchId` | 必需 |
| target_head | CHAR(40) | `String targetHead` | 必需 |
| change_policy | VARCHAR(24) | `String changePolicy` | 必需 |
| expected_tree_digest | CHAR(64) | `String expectedTreeDigest` | 可空 |
| old_archive_id | CHAR(36) | `String oldArchiveId` | 可空 |
| old_tree_digest | CHAR(64) | `String oldTreeDigest` | 可空 |
| old_published_head | CHAR(40) | `String oldPublishedHead` | 可空 |
| old_epoch | BIGINT | `Long oldEpoch` | 可空 |
| bootstrap_id | CHAR(36) | `String bootstrapId` | 可空 |
| bootstrap_sha256 | CHAR(64) | `String bootstrapSha256` | 可空 |
| bootstrap_storage_key | VARCHAR(512) | `String bootstrapStorageKey` | 可空 |
| new_workline_id | CHAR(36) | `String newWorklineId` | 必需 |
| new_epoch | BIGINT | `Long newEpoch` | 可空 |
| state | VARCHAR(24) | `String state` | 必需 |
| error_code | VARCHAR(64) | `String errorCode` | 可空 |
| created_at | DATETIME(6) | `LocalDateTime createdAt` | 必需 |
| updated_at | DATETIME(6) | `LocalDateTime updatedAt` | 必需 |

## pull_request
Java：`com.gitnova.entity.pullrequest.PullRequestRows.PullRequestRow`

| SQL列 | 类型 | Java字段 | nullable/生成 |
|---|---|---|---|
| id | BIGINT | `Long id` | 必需 |
| repo_id | BIGINT | `Long repoId` | 必需 |
| number | BIGINT | `Long number` | 必需 |
| author_id | BIGINT | `Long authorId` | 必需 |
| source_branch_id | BIGINT | `Long sourceBranchId` | 可空 |
| target_branch_id | BIGINT | `Long targetBranchId` | 可空 |
| source_branch_name | VARCHAR(100) | `String sourceBranchName` | 必需 |
| target_branch_name | VARCHAR(100) | `String targetBranchName` | 必需 |
| title | VARCHAR(255) | `String title` | 必需 |
| body | TEXT | `String body` | 必需 |
| status | VARCHAR(16) | `String status` | 必需 |
| draft | BOOLEAN | `Boolean draft` | 必需 |
| version | BIGINT | `Long version` | 必需 |
| created_repo_sequence | BIGINT | `Long createdRepoSequence` | 必需 |
| closed_repo_sequence | BIGINT | `Long closedRepoSequence` | 可空 |
| merged_repo_sequence | BIGINT | `Long mergedRepoSequence` | 可空 |
| closed_source_head | CHAR(40) | `String closedSourceHead` | 可空 |
| closed_target_head | CHAR(40) | `String closedTargetHead` | 可空 |
| closed_comparison_base | CHAR(40) | `String closedComparisonBase` | 可空 |
| source_workline_id | CHAR(36) | `String sourceWorklineId` | 可空 |
| merged_from_head | CHAR(40) | `String mergedFromHead` | 可空 |
| merged_target_head | CHAR(40) | `String mergedTargetHead` | 可空 |
| merged_commit | CHAR(40) | `String mergedCommit` | 可空 |
| create_request_key | VARCHAR(128) | `String createRequestKey` | 必需 |
| create_request_digest | CHAR(64) | `String createRequestDigest` | 必需 |
| created_at | DATETIME(6) | `LocalDateTime createdAt` | 必需 |
| updated_at | DATETIME(6) | `LocalDateTime updatedAt` | 必需 |
| merged_at | DATETIME(6) | `LocalDateTime mergedAt` | 可空 |
| open_slot | TINYINT | `Integer openSlot` | 可空 / 生成只读 |

## pull_request_revision
Java：`com.gitnova.entity.pullrequest.PullRequestRows.RevisionRow`

| SQL列 | 类型 | Java字段 | nullable/生成 |
|---|---|---|---|
| id | BIGINT | `Long id` | 必需 |
| pr_id | BIGINT | `Long prId` | 必需 |
| source_branch_id | BIGINT | `Long sourceBranchId` | 必需 |
| repo_sequence | BIGINT | `Long repoSequence` | 必需 |
| source_head | CHAR(40) | `String sourceHead` | 必需 |
| target_head_seen | CHAR(40) | `String targetHeadSeen` | 可空 |
| comparison_base | CHAR(40) | `String comparisonBase` | 可空 |
| source_event_id | CHAR(36) | `String sourceEventId` | 必需 |
| occurred_at | DATETIME(6) | `LocalDateTime occurredAt` | 必需 |

## pull_request_comment
Java：`com.gitnova.entity.pullrequest.PullRequestRows.CommentRow`

| SQL列 | 类型 | Java字段 | nullable/生成 |
|---|---|---|---|
| id | BIGINT | `Long id` | 必需 |
| pr_id | BIGINT | `Long prId` | 必需 |
| author_id | BIGINT | `Long authorId` | 必需 |
| body | TEXT | `String body` | 必需 |
| version | BIGINT | `Long version` | 必需 |
| request_key | VARCHAR(128) | `String requestKey` | 必需 |
| request_digest | CHAR(64) | `String requestDigest` | 必需 |
| reply_to | BIGINT | `Long replyTo` | 可空 |
| thread_root | BIGINT | `Long threadRoot` | 可空 |
| resolved | BOOLEAN | `Boolean resolved` | 必需 |
| deleted_at | DATETIME(6) | `LocalDateTime deletedAt` | 可空 |
| comparison_base | CHAR(40) | `String comparisonBase` | 可空 |
| source_head | CHAR(40) | `String sourceHead` | 可空 |
| path | VARCHAR(1024) | `String path` | 可空 |
| side | VARCHAR(5) | `String side` | 可空 |
| start_line | INT | `Integer startLine` | 可空 |
| end_line | INT | `Integer endLine` | 可空 |
| created_at | DATETIME(6) | `LocalDateTime createdAt` | 必需 |
| updated_at | DATETIME(6) | `LocalDateTime updatedAt` | 必需 |

## pull_request_merge
Java：`com.gitnova.entity.pullrequest.PullRequestRows.MergeRow`

| SQL列 | 类型 | Java字段 | nullable/生成 |
|---|---|---|---|
| merge_id | CHAR(36) | `String mergeId` | 必需 |
| pr_id | BIGINT | `Long prId` | 必需 |
| actor_id | BIGINT | `Long actorId` | 必需 |
| request_digest | CHAR(64) | `String requestDigest` | 必需 |
| expected_pr_version | BIGINT | `Long expectedPrVersion` | 必需 |
| source_head | CHAR(40) | `String sourceHead` | 必需 |
| target_head | CHAR(40) | `String targetHead` | 必需 |
| merge_base | CHAR(40) | `String mergeBase` | 可空 |
| strategy | VARCHAR(24) | `String strategy` | 必需 |
| fixed_message | TEXT | `String fixedMessage` | 必需 |
| fixed_timestamp | DATETIME(6) | `LocalDateTime fixedTimestamp` | 必需 |
| candidate_commit | CHAR(40) | `String candidateCommit` | 可空 |
| state | VARCHAR(24) | `String state` | 必需 |
| error_code | VARCHAR(64) | `String errorCode` | 可空 |
| created_at | DATETIME(6) | `LocalDateTime createdAt` | 必需 |
| updated_at | DATETIME(6) | `LocalDateTime updatedAt` | 必需 |

## pull_request_mutation
Java：`com.gitnova.entity.pullrequest.PullRequestRows.MutationRow`

| SQL列 | 类型 | Java字段 | nullable/生成 |
|---|---|---|---|
| mutation_id | CHAR(36) | `String mutationId` | 必需 |
| pr_id | BIGINT | `Long prId` | 必需 |
| actor_id | BIGINT | `Long actorId` | 必需 |
| request_key | VARCHAR(128) | `String requestKey` | 必需 |
| request_digest | CHAR(64) | `String requestDigest` | 必需 |
| action | VARCHAR(32) | `String action` | 必需 |
| result_json | JSON | `String resultJson` | 必需 |
| created_at | DATETIME(6) | `LocalDateTime createdAt` | 必需 |

## user_follow
Java：`com.gitnova.entity.social.SocialRows.FollowRow`

| SQL列 | 类型 | Java字段 | nullable/生成 |
|---|---|---|---|
| follower_id | BIGINT | `Long followerId` | 必需 |
| followee_id | BIGINT | `Long followeeId` | 必需 |
| created_at | DATETIME(6) | `LocalDateTime createdAt` | 必需 |

## repository_star
Java：`com.gitnova.entity.social.SocialRows.StarRow`

| SQL列 | 类型 | Java字段 | nullable/生成 |
|---|---|---|---|
| user_id | BIGINT | `Long userId` | 必需 |
| repo_id | BIGINT | `Long repoId` | 必需 |
| created_at | DATETIME(6) | `LocalDateTime createdAt` | 必需 |

## repository_watch
Java：`com.gitnova.entity.social.SocialRows.WatchRow`

| SQL列 | 类型 | Java字段 | nullable/生成 |
|---|---|---|---|
| user_id | BIGINT | `Long userId` | 必需 |
| repo_id | BIGINT | `Long repoId` | 必需 |
| mode | VARCHAR(16) | `String mode` | 必需 |
| include_pr | BOOLEAN | `Boolean includePr` | 必需 |
| include_comments | BOOLEAN | `Boolean includeComments` | 必需 |
| include_branch_updates | BOOLEAN | `Boolean includeBranchUpdates` | 必需 |
| version | BIGINT | `Long version` | 必需 |
| created_at | DATETIME(6) | `LocalDateTime createdAt` | 必需 |
| updated_at | DATETIME(6) | `LocalDateTime updatedAt` | 必需 |

## user_social_stats
Java：`com.gitnova.entity.social.SocialRows.UserStatsRow`

| SQL列 | 类型 | Java字段 | nullable/生成 |
|---|---|---|---|
| user_id | BIGINT | `Long userId` | 必需 |
| following_count | BIGINT | `Long followingCount` | 必需 |
| followers_count | BIGINT | `Long followersCount` | 必需 |
| version | BIGINT | `Long version` | 必需 |

## repo_social_stats
Java：`com.gitnova.entity.social.SocialRows.RepoStatsRow`

| SQL列 | 类型 | Java字段 | nullable/生成 |
|---|---|---|---|
| repo_id | BIGINT | `Long repoId` | 必需 |
| star_count | BIGINT | `Long starCount` | 必需 |
| version | BIGINT | `Long version` | 必需 |

## domain_event_outbox
Java：`com.gitnova.entity.activity.DomainEventRows.OutboxRow`

| SQL列 | 类型 | Java字段 | nullable/生成 |
|---|---|---|---|
| event_id | CHAR(36) | `String eventId` | 必需 |
| schema_version | INT | `Integer schemaVersion` | 必需 |
| type | VARCHAR(40) | `String type` | 必需 |
| scope_key | VARCHAR(128) | `String scopeKey` | 必需 |
| scope_sequence | BIGINT | `Long scopeSequence` | 可空 |
| causation_event_id | CHAR(36) | `String causationEventId` | 可空 |
| repo_id | BIGINT | `Long repoId` | 可空 |
| actor_id | BIGINT | `Long actorId` | 必需 |
| subject_id | VARCHAR(128) | `String subjectId` | 必需 |
| occurred_at | DATETIME(6) | `LocalDateTime occurredAt` | 必需 |
| visibility_at_creation | VARCHAR(16) | `String visibilityAtCreation` | 必需 |
| payload_json | JSON | `String payloadJson` | 必需 |
| payload_hash | CHAR(64) | `String payloadHash` | 必需 |
| status | VARCHAR(16) | `String status` | 必需 |
| claim_token | CHAR(36) | `String claimToken` | 可空 |
| lease_until | DATETIME(6) | `LocalDateTime leaseUntil` | 可空 |
| attempts | INT | `Integer attempts` | 必需 |
| next_attempt_at | DATETIME(6) | `LocalDateTime nextAttemptAt` | 必需 |
| error_code | VARCHAR(64) | `String errorCode` | 可空 |
| created_at | DATETIME(6) | `LocalDateTime createdAt` | 必需 |
| published_at | DATETIME(6) | `LocalDateTime publishedAt` | 可空 |

## domain_event_receipt
Java：`com.gitnova.entity.activity.DomainEventRows.ReceiptRow`

| SQL列 | 类型 | Java字段 | nullable/生成 |
|---|---|---|---|
| consumer_name | VARCHAR(64) | `String consumerName` | 必需 |
| event_id | CHAR(36) | `String eventId` | 必需 |
| payload_hash | CHAR(64) | `String payloadHash` | 必需 |
| processed_at | DATETIME(6) | `LocalDateTime processedAt` | 必需 |

## domain_event_failure
Java：`com.gitnova.entity.activity.DomainEventRows.FailureRow`

| SQL列 | 类型 | Java字段 | nullable/生成 |
|---|---|---|---|
| consumer_name | VARCHAR(64) | `String consumerName` | 必需 |
| event_id | CHAR(36) | `String eventId` | 必需 |
| payload_hash | CHAR(64) | `String payloadHash` | 必需 |
| envelope_json | LONGTEXT | `String envelopeJson` | 必需 |
| error_code | VARCHAR(64) | `String errorCode` | 必需 |
| attempts | INT | `Integer attempts` | 必需 |
| next_attempt_at | DATETIME(6) | `LocalDateTime nextAttemptAt` | 可空 |
| state | VARCHAR(16) | `String state` | 必需 |
| updated_at | DATETIME(6) | `LocalDateTime updatedAt` | 必需 |

## activity_entry
Java：`com.gitnova.entity.activity.DomainEventRows.ActivityRow`

| SQL列 | 类型 | Java字段 | nullable/生成 |
|---|---|---|---|
| id | BIGINT | `Long id` | 必需 |
| event_id | CHAR(36) | `String eventId` | 必需 |
| actor_id | BIGINT | `Long actorId` | 必需 |
| repo_id | BIGINT | `Long repoId` | 必需 |
| subject_type | VARCHAR(32) | `String subjectType` | 必需 |
| subject_id | VARCHAR(128) | `String subjectId` | 必需 |
| type | VARCHAR(40) | `String type` | 必需 |
| visibility_at_creation | VARCHAR(16) | `String visibilityAtCreation` | 必需 |
| occurred_at | DATETIME(6) | `LocalDateTime occurredAt` | 必需 |

## repo_counter
Java：`com.gitnova.entity.activity.DomainEventRows.RepoCounterRow`

| SQL列 | 类型 | Java字段 | nullable/生成 |
|---|---|---|---|
| repo_id | BIGINT | `Long repoId` | 必需 |
| next_pr_number | BIGINT | `Long nextPrNumber` | 必需 |
| event_sequence | BIGINT | `Long eventSequence` | 必需 |

## user_notification
Java：`com.gitnova.entity.notification.NotificationRows.NotificationRow`

| SQL列 | 类型 | Java字段 | nullable/生成 |
|---|---|---|---|
| id | BIGINT | `Long id` | 必需 |
| event_id | CHAR(36) | `String eventId` | 必需 |
| recipient_id | BIGINT | `Long recipientId` | 必需 |
| channel | VARCHAR(16) | `String channel` | 必需 |
| repo_id | BIGINT | `Long repoId` | 可空 |
| subject_type | VARCHAR(32) | `String subjectType` | 必需 |
| subject_id | VARCHAR(128) | `String subjectId` | 必需 |
| type | VARCHAR(40) | `String type` | 必需 |
| created_at | DATETIME(6) | `LocalDateTime createdAt` | 必需 |
| read_at | DATETIME(6) | `LocalDateTime readAt` | 可空 |
| version | BIGINT | `Long version` | 必需 |
