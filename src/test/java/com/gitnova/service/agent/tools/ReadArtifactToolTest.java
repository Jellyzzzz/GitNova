package com.gitnova.service.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gitnova.dto.ToolCall;
import com.gitnova.service.agent.AgentTestContexts;
import com.gitnova.service.agent.context.ToolObservationPreview;
import com.gitnova.service.agent.context.TokenEstimator;
import com.gitnova.service.agent.journal.RunJournal;
import com.gitnova.service.agent.model.MessageFactory;
import com.gitnova.service.agent.model.ModelRole;
import com.gitnova.service.agent.runtime.AgentExecutionContext;
import com.gitnova.service.agent.runtime.AgentRunContext;
import com.gitnova.service.agent.tool.ToolRegistry;
import com.gitnova.service.agent.tool.ToolResult;
import com.gitnova.service.agent.tool.ToolStatus;
import com.gitnova.storage.artifact.LocalArtifactStore;
import com.gitnova.storage.config.ArtifactStorageProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReadArtifactToolTest {
    @TempDir Path root;
    private final ObjectMapper mapper = new ObjectMapper();
    private final RunJournal journal = mock(RunJournal.class);
    private final AgentExecutionContext context = AgentTestContexts.agent(
            new AgentRunContext("run-1", 2L, "1/2", "a".repeat(40), "b".repeat(40)));

    @Test
    void shouldReadOriginalResultFromPreviewReferenceAndPreserveBothCallIds() throws Exception {
        var store = new LocalArtifactStore(new ArtifactStorageProperties(root, 16384, 4096), mapper);
        var original = ToolResult.success(mapper.createObjectNode().put("exitCode", 1)
                .put("stdout", "a long test log\n".repeat(500)).put("generationAfter", 8));
        var ref = store.saveToolResult(context, original);
        var renderer = new ToolObservationPreview(mapper, new TokenEstimator());
        var preview = renderer.preview("runCommand", original, ref, 1500);
        var factory = new MessageFactory(mapper);
        var commandMessage = factory.toolObservation(new ToolCall("command-1", "runCommand",
                mapper.createObjectNode()), preview);
        assertEquals("command-1", commandMessage.toolCallId());
        assertEquals(ModelRole.TOOL, commandMessage.role());

        when(journal.findArtifact(context.sessionId(), ref.artifactId())).thenReturn(Optional.of(ref));
        var registry = new ToolRegistry(List.of(new ReadArtifactTool(store, journal, mapper)));
        StringBuilder restored = new StringBuilder();
        long offset = 0;
        do {
            var args = mapper.createObjectNode().put("artifactId", ref.artifactId())
                    .put("offset", offset).put("maxBytes", 1024);
            var result = registry.execute(AgentTestContexts.toolExecution(context, 2, "read-1"),
                    ReadArtifactTool.NAME, args);
            assertEquals(ToolStatus.SUCCESS, result.status());
            assertFalse(result.truncated());
            var message = factory.tool(new ToolCall("read-1", ReadArtifactTool.NAME, args), result);
            assertEquals("read-1", message.toolCallId());
            restored.append(result.payload().path("content").asText());
            offset = result.payload().path("nextOffset").asLong();
            assertEquals(offset < ref.sizeBytes(), result.payload().path("hasMore").asBoolean());
        } while (offset < ref.sizeBytes());
        assertEquals(mapper.valueToTree(original), mapper.readTree(restored.toString()));
    }

    @Test
    void shouldRejectUncommittedOrOtherSessionReferenceEvenWhenFileExists() throws Exception {
        var store = new LocalArtifactStore(new ArtifactStorageProperties(root, 8192, 4096), mapper);
        var ref = store.saveToolResult(context, ToolResult.success(mapper.createObjectNode()));
        var tool = new ReadArtifactTool(store, journal, mapper);
        when(journal.findArtifact(context.sessionId(), ref.artifactId())).thenReturn(Optional.empty());
        var args = mapper.createObjectNode().put("artifactId", ref.artifactId()).put("offset", 0).put("maxBytes", 100);
        var result = tool.execute(AgentTestContexts.toolExecution(context, 1, "read-1"), args);
        assertEquals(ToolStatus.NOT_FOUND, result.status());
        assertEquals("ARTIFACT_NOT_FOUND", result.errorCode());
        verify(journal).findArtifact(context.sessionId(), ref.artifactId());
    }

    @Test
    void shouldRejectModelSuppliedScopeAndPathBySchema() {
        var store = new LocalArtifactStore(new ArtifactStorageProperties(root, 8192, 4096), mapper);
        var registry = new ToolRegistry(List.of(new ReadArtifactTool(store, journal, mapper)));
        var args = mapper.createObjectNode().put("artifactId", "a".repeat(64))
                .put("offset", 0).put("maxBytes", 100).put("sessionId", "another-session")
                .put("path", "../secret");
        var result = registry.execute(AgentTestContexts.toolExecution(context, 1, "read-1"), ReadArtifactTool.NAME, args);
        assertEquals("SCHEMA_VALIDATION_FAILED", result.errorCode());
        verifyNoInteractions(journal);
    }

    @Test
    void shouldReportMissingContentAndCorruptionWithoutLeakingFilesystemPath() throws Exception {
        var store = new LocalArtifactStore(new ArtifactStorageProperties(root, 8192, 4096), mapper);
        var ref = store.saveToolResult(context, ToolResult.success(mapper.createObjectNode()));
        when(journal.findArtifact(context.sessionId(), ref.artifactId())).thenReturn(Optional.of(ref));
        var tool = new ReadArtifactTool(store, journal, mapper);
        var args = mapper.createObjectNode().put("artifactId", ref.artifactId()).put("offset", 0).put("maxBytes", 100);
        Path file;
        try (var files = Files.walk(root)) {
            file = files.filter(Files::isRegularFile).findFirst().orElseThrow();
        }
        Files.writeString(file, "corrupt");
        var corrupt = tool.execute(AgentTestContexts.toolExecution(context, 1, "read-1"), args);
        assertEquals("ARTIFACT_READ_FAILED", corrupt.errorCode());
        assertFalse(corrupt.message().contains(root.toString()));
        Files.delete(file);
        assertEquals("ARTIFACT_CONTENT_MISSING",
                tool.execute(AgentTestContexts.toolExecution(context, 1, "read-2"), args).errorCode());
    }

    @Test
    void shouldRegisterAsSpringToolBean() {
        new ApplicationContextRunner()
                .withBean(ObjectMapper.class, () -> mapper)
                .withBean(RunJournal.class, () -> journal)
                .withBean(ArtifactStorageProperties.class, () -> new ArtifactStorageProperties(root, 8192, 4096))
                .withUserConfiguration(LocalArtifactStore.class, ReadArtifactTool.class, ToolRegistry.class)
                .run(application -> {
                    assertNull(application.getStartupFailure());
                    assertTrue(application.getBean(ToolRegistry.class).definitions().stream()
                            .anyMatch(definition -> definition.name().equals(ReadArtifactTool.NAME)));
                });
    }
}
