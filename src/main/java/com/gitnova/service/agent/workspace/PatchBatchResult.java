package com.gitnova.service.agent.workspace;

import java.util.List;
import java.util.Objects;

/**
 * Complete result of one fail-fast Workspace mutation batch.
 *
 * <p>A batch advances the Workspace generation at most once. PARTIAL_SUCCESS means a confirmed
 * prefix was applied and the remaining suffix was either failed or not attempted.</p>
 */
public record PatchBatchResult(
        PatchBatchStatus status,
        long expectedGeneration,
        long generationBefore,
        long generationAfter,
        List<PatchOperationResult> operationResults,
        String errorCode,
        String message
) {
    public PatchBatchResult {
        Objects.requireNonNull(status, "patch batch status must not be null");
        if (expectedGeneration < 0 || generationBefore < 0 || generationAfter < 0) {
            throw new IllegalArgumentException("patch batch generations must not be negative");
        }
        if (generationAfter < generationBefore || generationAfter > generationBefore + 1) {
            throw new IllegalArgumentException(
                    "generationAfter must equal generationBefore or generationBefore + 1"
            );
        }
        Objects.requireNonNull(operationResults, "operationResults must not be null");
        List<PatchOperationResult> copied = List.copyOf(operationResults);
        if (copied.isEmpty()) {
            throw new IllegalArgumentException("operationResults must not be empty");
        }
        for (int index = 0; index < copied.size(); index++) {
            PatchOperationResult result = Objects.requireNonNull(
                    copied.get(index),
                    "operation result must not be null"
            );
            if (result.index() != index) {
                throw new IllegalArgumentException(
                        "operation result indices must be contiguous and match list order"
                );
            }
        }
        operationResults = copied;

        if (status != PatchBatchStatus.CONFLICT
                && expectedGeneration != generationBefore) {
            throw new IllegalArgumentException(
                    "Non-conflict patch batch must start at expectedGeneration"
            );
        }

        if (status == PatchBatchStatus.SUCCESS) {
            if (errorCode != null || message != null) {
                throw new IllegalArgumentException(
                        "Successful patch batch must not contain error information"
                );
            }
        } else {
            if (errorCode == null || errorCode.isBlank()) {
                throw new IllegalArgumentException(
                        "Non-success patch batch must contain errorCode"
                );
            }
            if (message == null || message.isBlank()) {
                throw new IllegalArgumentException(
                        "Non-success patch batch must contain message"
                );
            }
        }
        validateOutcomeShape(
                status,
                generationBefore,
                generationAfter,
                copied
        );
    }

    public static PatchBatchResult success(
            WorkspaceMutationCommand command,
            long generationBefore,
            List<PatchOperationResult> results
    ) {
        requireMatchesCommand(command, results);
        return new PatchBatchResult(
                PatchBatchStatus.SUCCESS,
                command.expectedGeneration(),
                generationBefore,
                generationBefore + 1,
                results,
                null,
                null
        );
    }

    public static PatchBatchResult partialSuccess(
            WorkspaceMutationCommand command,
            long generationBefore,
            List<PatchOperationResult> results,
            String errorCode,
            String message
    ) {
        requireMatchesCommand(command, results);
        return new PatchBatchResult(
                PatchBatchStatus.PARTIAL_SUCCESS,
                command.expectedGeneration(),
                generationBefore,
                generationBefore + 1,
                results,
                errorCode,
                message
        );
    }

    public static PatchBatchResult failed(
            WorkspaceMutationCommand command,
            long generationBefore,
            List<PatchOperationResult> results,
            String errorCode,
            String message
    ) {
        requireMatchesCommand(command, results);
        return new PatchBatchResult(
                PatchBatchStatus.FAILED,
                command.expectedGeneration(),
                generationBefore,
                generationBefore,
                results,
                errorCode,
                message
        );
    }

    public static PatchBatchResult conflict(
            WorkspaceMutationCommand command,
            long currentGeneration,
            String errorCode,
            String message
    ) {
        Objects.requireNonNull(command, "command must not be null");
        List<PatchOperationResult> results = command.operations()
                .stream()
                .map(operation -> PatchOperationResult.notAttempted(
                        operation,
                        errorCode,
                        message
                ))
                .toList();
        return new PatchBatchResult(
                PatchBatchStatus.CONFLICT,
                command.expectedGeneration(),
                currentGeneration,
                currentGeneration,
                results,
                errorCode,
                message
        );
    }

    public boolean stateChanged() {
        return generationAfter > generationBefore;
    }

    public List<PatchOperationResult> appliedOperations() {
        return operationResults.stream()
                .filter(PatchOperationResult::applied)
                .toList();
    }

    private static void validateOutcomeShape(
            PatchBatchStatus status,
            long generationBefore,
            long generationAfter,
            List<PatchOperationResult> results
    ) {
        long applied = results.stream()
                .filter(result -> result.status() == PatchOperationStatus.APPLIED)
                .count();
        long failed = results.stream()
                .filter(result -> result.status() == PatchOperationStatus.FAILED)
                .count();
        long notAttempted = results.stream()
                .filter(result -> result.status() == PatchOperationStatus.NOT_ATTEMPTED)
                .count();

        switch (status) {
            case SUCCESS -> {
                if (applied != results.size() || generationAfter != generationBefore + 1) {
                    throw new IllegalArgumentException(
                            "SUCCESS requires every operation applied and generation advanced"
                    );
                }
            }
            case PARTIAL_SUCCESS -> {
                if (applied == 0 || failed != 1 || generationAfter != generationBefore + 1) {
                    throw new IllegalArgumentException(
                            "PARTIAL_SUCCESS requires an applied prefix, one failure, and generation advanced"
                    );
                }
                requireFailFastOrder(results);
            }
            case FAILED -> {
                if (applied != 0 || failed != 1 || generationAfter != generationBefore) {
                    throw new IllegalArgumentException(
                            "FAILED requires no applied operation, one failure, and unchanged generation"
                    );
                }
                requireFailFastOrder(results);
            }
            case CONFLICT -> {
                if (applied != 0 || failed != 0 || notAttempted != results.size()
                        || generationAfter != generationBefore) {
                    throw new IllegalArgumentException(
                            "CONFLICT requires every operation not attempted and unchanged generation"
                    );
                }
            }
        }
    }

    private static void requireFailFastOrder(List<PatchOperationResult> results) {
        boolean failed = false;
        for (PatchOperationResult result : results) {
            if (!failed && result.status() == PatchOperationStatus.APPLIED) {
                continue;
            }
            if (!failed && result.status() == PatchOperationStatus.FAILED) {
                failed = true;
                continue;
            }
            if (failed && result.status() == PatchOperationStatus.NOT_ATTEMPTED) {
                continue;
            }
            throw new IllegalArgumentException(
                    "operation results must follow APPLIED*, FAILED, NOT_ATTEMPTED*"
            );
        }
    }

    private static void requireMatchesCommand(
            WorkspaceMutationCommand command,
            List<PatchOperationResult> results
    ) {
        Objects.requireNonNull(command, "command must not be null");
        Objects.requireNonNull(results, "results must not be null");
        if (command.operations().size() != results.size()) {
            throw new IllegalArgumentException(
                    "operationResults must contain one result for every requested operation"
            );
        }
        for (int index = 0; index < results.size(); index++) {
            PatchOperation operation = command.operations().get(index);
            PatchOperationResult result = results.get(index);
            if (result == null
                    || operation.index() != result.index()
                    || operation.type() != result.type()
                    || !operation.filePath().equals(result.filePath())) {
                throw new IllegalArgumentException(
                        "operation result does not match requested operation at index " + index
                );
            }
        }
    }
}
