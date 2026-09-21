package com.gitnova.service.agent.workspace;

import com.gitnova.entity.agent.AgentWorkspaceEntity;
import com.gitnova.mapper.agent.AgentWorkspaceMapper;
import com.gitnova.service.agent.execution.AgentExecutionPersistenceException;
import com.gitnova.service.session.AgentSessionStore;
import com.gitnova.storage.RepoKey;
import com.gitnova.storage.config.WorkspaceStorageProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

import static com.gitnova.service.agent.workspace.PatchOperation.TextEdit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class LocalWorkspaceEditTest {
    @TempDir Path tempDir;

    @ParameterizedTest
    @MethodSource("validEdits")
    void shouldApplyAgainstOriginalCoordinates(String original, List<TextEdit> edits, String expected) throws Exception {
        assertEquals(expected, LocalWorkspaceGateway.applyExactEdits(original, edits));
        Fixture fixture = fixture(original);
        fixture.state().markValidationSucceeded(0);
        PatchBatchResult result = fixture.apply(0, "file.txt", edits);

        assertEquals(PatchBatchStatus.SUCCESS, result.status());
        assertEquals(expected, Files.readString(fixture.root().resolve("file.txt")));
        assertEquals(1, result.generationAfter());
        assertEquals(1, fixture.state().generation());
        assertNull(fixture.state().latestSuccessfulValidationGeneration());
        assertNotEquals(result.operationResults().get(0).beforeSha256(), result.operationResults().get(0).afterSha256());
        assertFalse(fixture.gateway().refreshWorkspace(fixture.id()).changed());
        try (var paths = Files.list(fixture.root())) {
            assertEquals(List.of("file.txt"), paths.map(path -> path.getFileName().toString()).toList());
        }
    }

    static Stream<Arguments> validEdits() {
        return Stream.of(
                Arguments.of("b|tail", List.of(new TextEdit("tail", "T"), new TextEdit("b", "longer")), "longer|T"),
                Arguments.of("foo\nbar\n", List.of(new TextEdit("foo", "foo bar"), new TextEdit("bar", "BAR")), "foo bar\nBAR\n"),
                Arguments.of("abcd", List.of(new TextEdit("ab", "X"), new TextEdit("cd", "YZ")), "XYZ"),
                Arguments.of("remove", List.of(new TextEdit("remove", "")), ""),
                Arguments.of("x y", List.of(new TextEdit(" ", "\t")), "x\ty"),
                Arguments.of("x", List.of(new TextEdit("x", " \n")), " \n"),
                Arguments.of("int n=1;", List.of(new TextEdit("n=1", "n=2")), "int n=2;"),
                Arguments.of("last\n", List.of(new TextEdit("last\n", "last")), "last"),
                Arguments.of("标签🙂", List.of(new TextEdit("标签", "标记")), "标记🙂")
        );
    }

    @ParameterizedTest
    @MethodSource("rejectedEdits")
    void shouldRejectWholeFileWithoutApplyingAnEarlierReplacement(String original, List<TextEdit> edits, String code) throws Exception {
        Fixture fixture = fixture(original);
        String fingerprint = fixture.state().contentFingerprint();
        PatchBatchResult result = fixture.apply(0, "file.txt", edits);

        assertEquals(PatchBatchStatus.FAILED, result.status());
        assertEquals(code, result.operationResults().get(0).errorCode());
        assertEquals(original, Files.readString(fixture.root().resolve("file.txt")));
        assertEquals(0, result.generationAfter());
        assertEquals(fingerprint, fixture.state().contentFingerprint());
        assertFalse(fixture.gateway().refreshWorkspace(fixture.id()).changed());
    }

    static Stream<Arguments> rejectedEdits() {
        return Stream.of(
                Arguments.of("first second", List.of(new TextEdit("first", "done"), new TextEdit("missing", "x")), "EDIT_TEXT_NOT_FOUND"),
                Arguments.of("aaa", List.of(new TextEdit("aa", "x")), "EDIT_TEXT_AMBIGUOUS"),
                Arguments.of("first x x", List.of(new TextEdit("first", "done"), new TextEdit("x", "y")), "EDIT_TEXT_AMBIGUOUS"),
                Arguments.of("abc", List.of(new TextEdit("ab", "x"), new TextEdit("bc", "y")), "EDIT_OVERLAP"),
                Arguments.of("x abc", List.of(new TextEdit("x", "q"), new TextEdit("ab", "1"), new TextEdit("bc", "2")), "EDIT_OVERLAP"),
                Arguments.of("abc", List.of(new TextEdit("abc", "x"), new TextEdit("b", "y")), "EDIT_OVERLAP"),
                Arguments.of("same", List.of(new TextEdit("same", "same")), "EDIT_HAS_NO_EFFECT"),
                Arguments.of("abc", List.of(new TextEdit("abc", "x\ry")), "INVALID_EDIT_TEXT"),
                Arguments.of("abc", List.of(new TextEdit("abc", "\uD800")), "INVALID_EDIT_TEXT")
        );
    }

    @Test
    void shouldPreserveBomAndCrLfWithoutNormalizingOtherCharacters() throws Exception {
        Fixture fixture = fixture("\uFEFFfirst\r\n宽字符＝“value”\r\nlast\r\n");
        PatchBatchResult result = fixture.apply(0, "file.txt", List.of(
                new TextEdit("first\n", "new\nline\n"), new TextEdit("last\r\n", "end")));
        assertEquals(PatchBatchStatus.SUCCESS, result.status());
        assertEquals("\uFEFFnew\r\nline\r\n宽字符＝“value”\r\nend", Files.readString(fixture.root().resolve("file.txt")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"one\r\ntwo\n", "one\rtwo", "one\0two"})
    void shouldRejectUnsupportedTextWithoutRewritingIt(String original) throws Exception {
        Fixture fixture = fixture(original);
        PatchBatchResult result = fixture.apply(0, "file.txt", List.of(new TextEdit("one", "new")));
        assertEquals(PatchBatchStatus.FAILED, result.status());
        assertEquals(original, Files.readString(fixture.root().resolve("file.txt")));
        assertEquals(0, fixture.state().generation());
    }

    @Test
    void shouldRejectMalformedUtf8() throws Exception {
        Fixture fixture = fixture("one");
        byte[] malformed = {(byte) 0xc3, 0x28};
        Files.write(fixture.root().resolve("file.txt"), malformed);
        fixture.gateway().refreshWorkspace(fixture.id());
        PatchBatchResult result = fixture.apply(1, "file.txt", List.of(new TextEdit("one", "new")));
        assertEquals("FILE_NOT_UTF8_TEXT", result.operationResults().get(0).errorCode());
        assertArrayEquals(malformed, Files.readAllBytes(fixture.root().resolve("file.txt")));
        assertEquals(1, result.generationAfter());
    }

    @Test
    void shouldRejectOversizedResultBeforeWriting() throws Exception {
        String original = "x".repeat(WorkspaceGateway.MAX_DIFF_FILE_BYTES - 1) + "y";
        Fixture fixture = fixture(original);
        PatchBatchResult result = fixture.apply(0, "file.txt", List.of(new TextEdit("y", "zz")));
        assertEquals("FILE_TOO_LARGE", result.operationResults().get(0).errorCode());
        assertEquals(original, Files.readString(fixture.root().resolve("file.txt")));
        assertEquals(0, fixture.state().generation());
    }

    @Test
    void shouldRejectStaleGenerationAfterAnExternalWrite() throws Exception {
        Fixture fixture = fixture("old");
        Files.writeString(fixture.root().resolve("file.txt"), "user change");
        PatchBatchResult result = fixture.apply(0, "file.txt", List.of(new TextEdit("old", "agent change")));
        assertEquals(PatchBatchStatus.CONFLICT, result.status());
        assertEquals("STALE_WORKSPACE_GENERATION", result.errorCode());
        assertEquals(1, result.generationAfter()); // The external change, not this rejected edit.
        assertEquals("user change", Files.readString(fixture.root().resolve("file.txt")));
    }

    @Test
    void shouldNotAutomaticallyRepeatAnAlreadyAppliedEdit() throws Exception {
        Fixture fixture = fixture("old");
        List<TextEdit> edits = List.of(new TextEdit("old", "new"));
        assertEquals(PatchBatchStatus.SUCCESS, fixture.apply(0, "file.txt", edits).status());
        assertEquals("STALE_WORKSPACE_GENERATION", fixture.apply(0, "file.txt", edits).errorCode());
        assertEquals("EDIT_TEXT_NOT_FOUND", fixture.apply(1, "file.txt", edits).operationResults().get(0).errorCode());
        assertEquals(1, fixture.state().generation());
        assertEquals("new", Files.readString(fixture.root().resolve("file.txt")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"../outside.txt", "/tmp/outside.txt", "C:/outside.txt", ".git/config", ".gitnova/internal", "x//y", "artifact://tool-results/24/stdout.txt"})
    void shouldRejectUnsafePaths(String path) throws Exception {
        Fixture fixture = fixture("old");
        PatchBatchResult result = fixture.apply(0, path, List.of(new TextEdit("old", "new")));
        assertEquals(PatchBatchStatus.FAILED, result.status());
        assertEquals(0, fixture.state().generation());
        assertEquals("old", Files.readString(fixture.root().resolve("file.txt")));
    }

    @Test
    void shouldRejectMissingFilesAndSymlinks() throws Exception {
        Fixture fixture = fixture("old");
        Path outside = tempDir.resolve("outside.txt");
        Files.writeString(outside, "old");
        Files.createSymbolicLink(fixture.root().resolve("link.txt"), outside);
        assertEquals("UNSAFE_WORKSPACE_PATH", fixture.apply(0, "link.txt", List.of(new TextEdit("old", "new"))).operationResults().get(0).errorCode());
        assertEquals("FILE_NOT_FOUND", fixture.apply(0, "missing.txt", List.of(new TextEdit("old", "new"))).operationResults().get(0).errorCode());
        assertEquals("old", Files.readString(outside));
        assertEquals(0, fixture.state().generation());
    }

    @Test
    void shouldRespectWorkspaceLockAndRejectOldFenceAfterTakeover() throws Exception {
        Fixture fixture = fixture("old");
        var readLock = fixture.state().lock().readLock();
        readLock.lock();
        var pool = Executors.newSingleThreadExecutor();
        var started = new CountDownLatch(1);
        try {
            var pending = pool.submit(() -> {
                started.countDown();
                return fixture.apply(0, "file.txt", List.of(new TextEdit("old", "new")));
            });
            assertTrue(started.await(1, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> pending.get(100, TimeUnit.MILLISECONDS));
            assertEquals("old", Files.readString(fixture.root().resolve("file.txt")));
            readLock.unlock();
            assertEquals(PatchBatchStatus.SUCCESS, pending.get(2, TimeUnit.SECONDS).status());
        } finally {
            if (fixture.state().lock().getReadHoldCount() > 0) readLock.unlock();
            pool.shutdownNow();
        }
        var takeover = new WorkspaceExecutionPermit("new-run", fixture.id(), 2);
        fixture.gateway().applyPatch(fixture.id(), takeover, new WorkspaceMutationCommand(1,
                List.of(PatchOperation.edit(0, "file.txt", List.of(new TextEdit("new", "latest"))))));
        PatchBatchResult stale = fixture.apply(2, "file.txt", List.of(new TextEdit("latest", "stale")));
        assertEquals("STALE_WORKSPACE_FENCE", stale.errorCode());
        assertEquals("latest", Files.readString(fixture.root().resolve("file.txt")));
    }

    @Test
    void shouldPersistChangedGenerationAndPropagateFailureAfterTheFileWrite() throws Exception {
        Fixture fixture = fixture("old");
        AgentWorkspaceMapper mapper = mock(AgentWorkspaceMapper.class);
        AgentSessionStore store = mock(AgentSessionStore.class);
        AgentWorkspaceEntity row = new AgentWorkspaceEntity();
        row.setWorkspaceId(fixture.id().toString());
        row.setSessionId("edit-session");
        row.setRepoKey("1/10");
        row.setBaseRevision("a".repeat(40));
        row.setProviderType("local-filesystem");
        row.setProviderRef(fixture.root().toString());
        row.setWorkspaceEpoch(0L);
        row.setGeneration(0L);
        row.setContentFingerprint(fixture.state().contentFingerprint());
        row.setLastAcceptedFencingToken(0L);
        when(mapper.selectReadyForRegistration(fixture.id().toString())).thenReturn(row);
        var registry = new LocalWorkspaceRegistry(mapper, new WorkspaceStorageProperties(tempDir), store);
        var gateway = new LocalWorkspaceGateway(registry);
        var permit = new WorkspaceExecutionPermit("run-edit", fixture.id(), 1);
        gateway.applyPatch(fixture.id(), permit, new WorkspaceMutationCommand(0,
                List.of(PatchOperation.edit(0, "file.txt", List.of(new TextEdit("old", "new"))))));

        var change = ArgumentCaptor.forClass(AgentSessionStore.WorkspaceStateChange.class);
        verify(store).recordWorkspaceState(change.capture());
        assertEquals(1, row.getGeneration());
        assertEquals(WorkspaceTreeFingerprint.capture(fixture.root()), row.getContentFingerprint());

        var failure = new AgentExecutionPersistenceException(AgentExecutionPersistenceException.Code.PERSISTENCE_FAILURE, "simulated DB failure");
        doThrow(failure).when(store).recordWorkspaceState(any());
        assertSame(failure, assertThrows(AgentExecutionPersistenceException.class,
                () -> gateway.applyPatch(fixture.id(), permit, new WorkspaceMutationCommand(1,
                        List.of(PatchOperation.edit(0, "file.txt", List.of(new TextEdit("new", "written"))))))));
        assertEquals("written", Files.readString(fixture.root().resolve("file.txt")));
        assertEquals(1, row.getGeneration()); // Only the earlier confirmed DB commit is acknowledged.
        assertEquals(2, registry.require(fixture.id()).generation());
    }

    private Fixture fixture(String original) throws Exception {
        WorkspaceId id = WorkspaceId.generate();
        Path root = Files.createDirectory(tempDir.resolve(id.toString()));
        Files.writeString(root.resolve("file.txt"), original, StandardCharsets.UTF_8);
        LocalWorkspaceRegistry registry = new LocalWorkspaceRegistry();
        registry.register(new WorkspaceHandle(id, RepoKey.of(1, 10), SnapshotScope.of("a".repeat(40)), root, WorkspaceStatus.READY, 0));
        return new Fixture(id, root, registry.require(id), new LocalWorkspaceGateway(registry));
    }

    private record Fixture(WorkspaceId id, Path root, LocalWorkspaceRegistry.LocalWorkspaceState state, LocalWorkspaceGateway gateway) {
        PatchBatchResult apply(long generation, String path, List<TextEdit> edits) {
            return gateway.applyPatch(id, new WorkspaceExecutionPermit("run-edit", id, 1),
                    new WorkspaceMutationCommand(generation, List.of(PatchOperation.edit(0, path, edits))));
        }
    }
}
