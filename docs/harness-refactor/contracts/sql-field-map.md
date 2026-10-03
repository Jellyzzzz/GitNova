# SQL行字段字典（由随包DDL生成）

MyBatis行使用可变POJO，public字段可被MyBatis反射映射；也可以按完全相同字段生成getter/setter，不改变列定义。DATETIME按UTC LocalDateTime映射；对外DTO使用Instant。generated openSlot只读，不写INSERT。

## agent_session → SessionRow

| SQL列 | Java字段 | 类型 | nullable |
|---|---|---|---|
| session_id | sessionId | String | False |
| creation_idempotency_key | creationIdempotencyKey | String | False |
| created_by_actor_id | createdByActorId | Long | False |
| repo_id | repoId | Long | False |
| repo_key | repoKey | String | False |
| status | status | String | False |
| version | version | Long | False |
| created_at | createdAt | LocalDateTime | False |
| updated_at | updatedAt | LocalDateTime | False |
| closed_at | closedAt | LocalDateTime | True |
| execution_backend | executionBackend | String | False |
| source_branch_name | sourceBranchName | String | True |
| base_commit | baseCommit | String | True |
| agent_branch_name | agentBranchName | String | True |
| active_task_id | activeTaskId | String | True |
| last_published_head | lastPublishedHead | String | True |
| latest_archive_id | latestArchiveId | String | True |
| workflow_state | workflowState | String | False |
| current_runner_epoch | currentRunnerEpoch | Long | False |
| creation_request_digest | creationRequestDigest | String | True |

## agent_sandbox_binding → BindingRow

| SQL列 | Java字段 | 类型 | nullable |
|---|---|---|---|
| session_id | sessionId | String | False |
| runner_epoch | runnerEpoch | Long | False |
| create_operation_id | createOperationId | String | False |
| sandbox_id | sandboxId | String | True |
| volume_name | volumeName | String | False |
| status | status | String | False |
| token_salt | tokenSalt | String | False |
| image_ref | imageRef | String | False |
| runtime_config_json | runtimeConfigJson | String | False |
| runtime_config_digest | runtimeConfigDigest | String | False |
| last_archived_sequence | lastArchivedSequence | Long | False |
| expires_at | expiresAt | LocalDateTime | True |
| last_observed_at | lastObservedAt | LocalDateTime | True |
| last_used_at | lastUsedAt | LocalDateTime | False |
| operation_owner | operationOwner | String | True |
| operation_until | operationUntil | LocalDateTime | True |
| version | version | Long | False |
| last_error_code | lastErrorCode | String | True |
| created_at | createdAt | LocalDateTime | False |
| updated_at | updatedAt | LocalDateTime | False |

## agent_control_task → TaskRow

| SQL列 | Java字段 | 类型 | nullable |
|---|---|---|---|
| task_id | taskId | String | False |
| session_id | sessionId | String | False |
| idempotency_key | idempotencyKey | String | False |
| actor_id | actorId | Long | False |
| task_mode | taskMode | String | False |
| message | message | String | False |
| request_digest | requestDigest | String | False |
| status | status | String | False |
| settlement_status | settlementStatus | String | False |
| publication_status | publicationStatus | String | False |
| current_attempt_id | currentAttemptId | String | True |
| attempt_number | attemptNumber | Long | False |
| answer_event_id | answerEventId | String | True |
| terminal_reason | terminalReason | String | True |
| cancel_requested | cancelRequested | Integer | False |
| version | version | Long | False |
| created_at | createdAt | LocalDateTime | False |
| updated_at | updatedAt | LocalDateTime | False |
| finished_at | finishedAt | LocalDateTime | True |

## agent_control_attempt → AttemptRow

| SQL列 | Java字段 | 类型 | nullable |
|---|---|---|---|
| attempt_id | attemptId | String | False |
| task_id | taskId | String | False |
| session_id | sessionId | String | False |
| attempt_number | attemptNumber | Long | False |
| runner_epoch | runnerEpoch | Long | False |
| status | status | String | False |
| expected_published_head | expectedPublishedHead | String | False |
| deadline_at | deadlineAt | LocalDateTime | False |
| config_digest | configDigest | String | False |
| answer_event_id | answerEventId | String | True |
| terminal_reason | terminalReason | String | True |
| created_at | createdAt | LocalDateTime | False |
| finished_at | finishedAt | LocalDateTime | True |

## agent_control_command → CommandRow

| SQL列 | Java字段 | 类型 | nullable |
|---|---|---|---|
| command_id | commandId | String | False |
| session_id | sessionId | String | False |
| runner_epoch | runnerEpoch | Long | False |
| task_id | taskId | String | True |
| attempt_id | attemptId | String | True |
| type | type | String | False |
| envelope_json | envelopeJson | String | False |
| envelope_digest | envelopeDigest | String | False |
| status | status | String | False |
| receipt_json | receiptJson | String | True |
| send_count | sendCount | Integer | False |
| next_attempt_at | nextAttemptAt | LocalDateTime | False |
| last_error_code | lastErrorCode | String | True |
| created_at | createdAt | LocalDateTime | False |
| updated_at | updatedAt | LocalDateTime | False |

## agent_event_archive → EventRow

| SQL列 | Java字段 | 类型 | nullable |
|---|---|---|---|
| archive_offset | archiveOffset | Long | False |
| event_id | eventId | String | False |
| session_id | sessionId | String | False |
| runner_epoch | runnerEpoch | Long | False |
| sequence | sequence | Long | False |
| task_id | taskId | String | True |
| attempt_id | attemptId | String | True |
| type | type | String | False |
| event_json | eventJson | String | False |
| event_digest | eventDigest | String | False |
| occurred_at | occurredAt | LocalDateTime | False |
| archived_at | archivedAt | LocalDateTime | False |

## agent_snapshot_archive → ArchiveRow

| SQL列 | Java字段 | 类型 | nullable |
|---|---|---|---|
| archive_id | archiveId | String | False |
| session_id | sessionId | String | False |
| export_id | exportId | String | False |
| runner_epoch | runnerEpoch | Long | False |
| task_id | taskId | String | True |
| attempt_id | attemptId | String | True |
| through_sequence | throughSequence | Long | False |
| tree_digest | treeDigest | String | False |
| state_digest | stateDigest | String | False |
| bundle_sha256 | bundleSha256 | String | False |
| bundle_bytes | bundleBytes | Long | False |
| storage_key | storageKey | String | False |
| manifest_json | manifestJson | String | False |
| execution_outcome | executionOutcome | String | False |
| status | status | String | False |
| settled_published_head | settledPublishedHead | String | True |
| settled_outcome | settledOutcome | String | True |
| settlement_command_id | settlementCommandId | String | True |
| created_at | createdAt | LocalDateTime | False |
| updated_at | updatedAt | LocalDateTime | False |

## agent_publication → PublicationRow

| SQL列 | Java字段 | 类型 | nullable |
|---|---|---|---|
| publication_id | publicationId | String | False |
| session_id | sessionId | String | False |
| export_id | exportId | String | False |
| archive_id | archiveId | String | False |
| task_id | taskId | String | True |
| branch_name | branchName | String | False |
| expected_head | expectedHead | String | False |
| tree_digest | treeDigest | String | False |
| message | message | String | False |
| author_id | authorId | Long | False |
| commit_epoch_millis | commitEpochMillis | Long | False |
| result_commit | resultCommit | String | True |
| status | status | String | False |
| error_code | errorCode | String | True |
| version | version | Long | False |
| created_at | createdAt | LocalDateTime | False |
| updated_at | updatedAt | LocalDateTime | False |

## pull_request → PullRequestRow

| SQL列 | Java字段 | 类型 | nullable |
|---|---|---|---|
| id | id | Long | False |
| repo_id | repoId | Long | False |
| source_branch | sourceBranch | String | False |
| target_branch | targetBranch | String | False |
| author_id | authorId | Long | False |
| agent_session_id | agentSessionId | String | True |
| title | title | String | False |
| body | body | String | False |
| status | status | String | False |
| draft | draft | Integer | False |
| revision | revision | Long | False |
| open_slot | openSlot | Integer | True |
| merged_from_head | mergedFromHead | String | True |
| merged_commit | mergedCommit | String | True |
| merge_method | mergeMethod | String | True |
| created_at | createdAt | LocalDateTime | False |
| updated_at | updatedAt | LocalDateTime | False |
| merged_at | mergedAt | LocalDateTime | True |

## pull_request_comment → CommentRow

| SQL列 | Java字段 | 类型 | nullable |
|---|---|---|---|
| id | id | Long | False |
| pr_id | prId | Long | False |
| author_id | authorId | Long | False |
| idempotency_key | idempotencyKey | String | False |
| body | body | String | False |
| source_head | sourceHead | String | True |
| created_at | createdAt | LocalDateTime | False |

## pull_request_merge → MergeRow

| SQL列 | Java字段 | 类型 | nullable |
|---|---|---|---|
| id | id | String | False |
| pr_id | prId | Long | False |
| idempotency_key | idempotencyKey | String | False |
| request_digest | requestDigest | String | False |
| actor_id | actorId | Long | False |
| expected_revision | expectedRevision | Long | False |
| source_head | sourceHead | String | False |
| target_head | targetHead | String | False |
| method | method | String | False |
| commit_epoch_millis | commitEpochMillis | Long | False |
| message | message | String | False |
| candidate_commit | candidateCommit | String | True |
| status | status | String | False |
| error_json | errorJson | String | True |
| created_at | createdAt | LocalDateTime | False |
| updated_at | updatedAt | LocalDateTime | False |
