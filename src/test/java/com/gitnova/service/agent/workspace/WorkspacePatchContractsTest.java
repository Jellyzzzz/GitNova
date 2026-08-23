package com.gitnova.service.agent.workspace;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspacePatchContractsTest {

    private static final String SHA_A = "a".repeat(64);
    private static final String SHA_B = "b".repeat(64);
    private static final String SHA_C = "c".repeat(64);

    @Test
    void shouldCreateOrderedMutationCommandAndDefensivelyCopyOperations() {
        List<PatchOperation> mutable = new ArrayList<>(List.of(
                PatchOperation.create(0, "src/A.java", "class A {}"),
                PatchOperation.update(1, "src/B.java", "@@ -1 +1 @@"),
                PatchOperation.delete(2, "src/C.java")
        ));

        WorkspaceMutationCommand command = new WorkspaceMutationCommand(3, mutable);
        mutable.clear();

        assertEquals(3, command.expectedGeneration());
        assertEquals(3, command.operations().size());
        assertThrows(
                UnsupportedOperationException.class,
                () -> command.operations().add(
                        PatchOperation.delete(3, "src/D.java")
                )
        );
    }

    @Test
    void shouldRejectNonContiguousOrDuplicateOperations() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new WorkspaceMutationCommand(
                        0,
                        List.of(PatchOperation.delete(1, "src/A.java"))
                )
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> new WorkspaceMutationCommand(
                        0,
                        List.of(
                                PatchOperation.create(0, "src/A.java", "class A {}"),
                                PatchOperation.delete(1, "src/A.java")
                        )
                )
        );
    }

    @Test
    void shouldRejectFieldsThatDoNotMatchOperationType() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new PatchOperation(
                        0,
                        PatchOperationType.CREATE,
                        "src/A.java",
                        "unexpected patch",
                        "class A {}"
                )
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> PatchOperation.update(0, "src/A.java", "   ")
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> new PatchOperation(
                        0,
                        PatchOperationType.DELETE,
                        "src/A.java",
                        null,
                        "unexpected content"
                )
        );
    }

    @Test
    void shouldRepresentFullyAppliedBatchAndAdvanceOneGeneration() {
        WorkspaceMutationCommand command = command();
        List<PatchOperationResult> results = List.of(
                PatchOperationResult.applied(command.operations().get(0), null, SHA_A),
                PatchOperationResult.applied(command.operations().get(1), SHA_A, SHA_B),
                PatchOperationResult.applied(command.operations().get(2), SHA_C, null)
        );

        PatchBatchResult batch = PatchBatchResult.success(command, 3, results);

        assertEquals(PatchBatchStatus.SUCCESS, batch.status());
        assertEquals(3, batch.generationBefore());
        assertEquals(4, batch.generationAfter());
        assertTrue(batch.stateChanged());
        assertEquals(3, batch.appliedOperations().size());
    }

    @Test
    void shouldRepresentFailFastPartialSuccessWithAuthoritativeState() {
        WorkspaceMutationCommand command = command();
        List<PatchOperationResult> results = List.of(
                PatchOperationResult.applied(command.operations().get(0), null, SHA_A),
                PatchOperationResult.failed(
                        command.operations().get(1),
                        SHA_B,
                        "PATCH_DOES_NOT_APPLY",
                        "Update hunk does not match current file"
                ),
                PatchOperationResult.notAttempted(command.operations().get(2))
        );

        PatchBatchResult batch = PatchBatchResult.partialSuccess(
                command,
                3,
                results,
                "PATCH_OPERATION_FAILED",
                "One operation was applied before the next operation failed"
        );

        assertEquals(PatchBatchStatus.PARTIAL_SUCCESS, batch.status());
        assertEquals(4, batch.generationAfter());
        assertTrue(batch.stateChanged());
        assertEquals(1, batch.appliedOperations().size());
        assertEquals(PatchOperationStatus.FAILED, batch.operationResults().get(1).status());
        assertEquals(
                PatchOperationStatus.NOT_ATTEMPTED,
                batch.operationResults().get(2).status()
        );
    }

    @Test
    void shouldRepresentFailureBeforeAnyMutationWithoutAdvancingGeneration() {
        WorkspaceMutationCommand command = command();
        List<PatchOperationResult> results = List.of(
                PatchOperationResult.failed(
                        command.operations().get(0),
                        null,
                        "FILE_ALREADY_EXISTS",
                        "CREATE target already exists"
                ),
                PatchOperationResult.notAttempted(command.operations().get(1)),
                PatchOperationResult.notAttempted(command.operations().get(2))
        );

        PatchBatchResult batch = PatchBatchResult.failed(
                command,
                3,
                results,
                "PATCH_OPERATION_FAILED",
                "The first operation failed"
        );

        assertEquals(PatchBatchStatus.FAILED, batch.status());
        assertEquals(3, batch.generationAfter());
        assertFalse(batch.stateChanged());
        assertTrue(batch.appliedOperations().isEmpty());
    }

    @Test
    void shouldRepresentStaleGenerationAsNoAttemptConflict() {
        WorkspaceMutationCommand command = command();

        PatchBatchResult batch = PatchBatchResult.conflict(
                command,
                4,
                "STALE_WORKSPACE_GENERATION",
                "Expected generation no longer matches"
        );

        assertEquals(PatchBatchStatus.CONFLICT, batch.status());
        assertEquals(3, batch.expectedGeneration());
        assertEquals(4, batch.generationBefore());
        assertEquals(4, batch.generationAfter());
        assertFalse(batch.stateChanged());
        assertTrue(batch.operationResults().stream().allMatch(
                result -> result.status() == PatchOperationStatus.NOT_ATTEMPTED
        ));
        assertTrue(batch.operationResults().stream().allMatch(
                result -> "STALE_WORKSPACE_GENERATION".equals(result.errorCode())
        ));
    }

    @Test
    void shouldRejectOutcomeThatBreaksFailFastOrdering() {
        WorkspaceMutationCommand command = command();
        List<PatchOperationResult> invalid = List.of(
                PatchOperationResult.failed(
                        command.operations().get(0),
                        null,
                        "FILE_ALREADY_EXISTS",
                        "CREATE target already exists"
                ),
                PatchOperationResult.applied(command.operations().get(1), SHA_A, SHA_B),
                PatchOperationResult.notAttempted(command.operations().get(2))
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> new PatchBatchResult(
                        PatchBatchStatus.PARTIAL_SUCCESS,
                        3,
                        3,
                        4,
                        invalid,
                        "PATCH_OPERATION_FAILED",
                        "Invalid ordering"
                )
        );
    }

    @Test
    void shouldRejectDigestThatCannotSupportRecoveryReconciliation() {
        PatchOperation operation = PatchOperation.create(
                0,
                "src/A.java",
                "class A {}"
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> PatchOperationResult.applied(operation, null, "not-a-sha256")
        );
    }

    @Test
    void shouldRejectSuccessfulOutcomeStartingFromUnexpectedGeneration() {
        WorkspaceMutationCommand command = command();
        List<PatchOperationResult> results = List.of(
                PatchOperationResult.applied(command.operations().get(0), null, SHA_A),
                PatchOperationResult.applied(command.operations().get(1), SHA_A, SHA_B),
                PatchOperationResult.applied(command.operations().get(2), SHA_C, null)
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> PatchBatchResult.success(command, 4, results)
        );
    }

    @Test
    void shouldRejectNoOpUpdateReportedAsApplied() {
        PatchOperation operation = PatchOperation.update(
                0,
                "src/A.java",
                "@@ -1 +1 @@"
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> PatchOperationResult.applied(operation, SHA_A, SHA_A)
        );
    }

    private WorkspaceMutationCommand command() {
        return new WorkspaceMutationCommand(
                3,
                List.of(
                        PatchOperation.create(0, "src/A.java", "class A {}"),
                        PatchOperation.update(1, "src/B.java", "@@ -1 +1 @@"),
                        PatchOperation.delete(2, "src/C.java")
                )
        );
    }
}
