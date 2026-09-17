package com.gitnova.service.agent.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gitnova.service.agent.context.ContextBudget;
import com.gitnova.service.agent.context.SessionContextService;
import com.gitnova.service.agent.context.ObservationPolicy;
import com.gitnova.service.agent.context.TokenEstimator;
import com.gitnova.service.agent.context.ToolObservationPreview;
import com.gitnova.service.agent.execution.AgentTaskRunStore;
import com.gitnova.service.agent.execution.CreateTaskCommand;
import com.gitnova.service.agent.journal.RunJournal;
import com.gitnova.service.agent.model.MessageFactory;
import com.gitnova.service.agent.model.ModelGateway;
import com.gitnova.service.agent.persistence.CanonicalJsonCodec;
import com.gitnova.service.agent.prompt.PromptAssembler;
import com.gitnova.service.agent.task.AgentTaskService;
import com.gitnova.service.agent.tool.ToolRegistry;
import com.gitnova.service.agent.tool.ToolSetResolver;
import com.gitnova.service.agent.tool.ToolSetSnapFactory;
import com.gitnova.service.agent.tools.FinishTaskTool;
import com.gitnova.service.agent.tools.ReadArtifactTool;
import com.gitnova.service.agent.workspace.WorkspaceGateway;
import com.gitnova.service.session.AgentSession;
import com.gitnova.service.session.AgentSessionService;
import com.gitnova.storage.RepoKey;
import com.gitnova.storage.artifact.LocalArtifactStore;
import com.gitnova.storage.config.ArtifactStorageProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentObservationConfigurationTest {
    @TempDir Path root;

    @Test
    void springWiresArtifactDependenciesAndTaskFreezesConfiguredBudgetsAndReader() {
        ObjectMapper mapper = new ObjectMapper();
        CanonicalJsonCodec codec = new CanonicalJsonCodec(mapper);
        RunJournal journal = mock(RunJournal.class);
        var store = new LocalArtifactStore(new ArtifactStorageProperties(root, 65536, 4096), mapper);
        var registry = new ToolRegistry(List.of(new FinishTaskTool(mapper), new ReadArtifactTool(store, journal, mapper)));
        var snapshots = new ToolSetSnapFactory(codec);
        var sessions = mock(AgentSessionService.class);
        var tasks = mock(AgentTaskRunStore.class);

        new ApplicationContextRunner().withUserConfiguration(AgentRuntimeConfiguration.class)
                .withBean(ObjectMapper.class, () -> mapper)
                .withBean(ModelGateway.class, () -> mock(ModelGateway.class))
                .withBean(PromptAssembler.class, () -> mock(PromptAssembler.class))
                .withBean(MessageFactory.class, () -> new MessageFactory(mapper))
                .withBean(ToolRegistry.class, () -> registry)
                .withBean(WorkspaceGateway.class, () -> mock(WorkspaceGateway.class))
                .withBean(ToolSetResolver.class, () -> new ToolSetResolver(registry, snapshots))
                .withBean(ToolSetSnapFactory.class, () -> snapshots)
                .withBean(RunJournal.class, () -> journal)
                .withBean(CanonicalJsonCodec.class, () -> codec)
                .withBean(LocalArtifactStore.class, () -> store)
                .withBean(ToolObservationPreview.class, () -> new ToolObservationPreview(mapper, new TokenEstimator()))
                .withBean(AgentSessionService.class, () -> sessions)
                .withBean(AgentTaskRunStore.class, () -> tasks)
                .withBean(AgentTaskService.class)
                .withBean(TokenEstimator.class, TokenEstimator::new)
                .withBean(SessionContextService.class, () -> mock(SessionContextService.class))
                .withPropertyValues("gitnova.agent.runtime.model=test-model",
                        "gitnova.agent.runtime.max-model-calls=10", "gitnova.agent.runtime.max-tool-calls=30",
                        "gitnova.agent.runtime.max-output-tokens=4000",
                        "gitnova.agent.runtime.context.context-window-tokens=32000",
                        "gitnova.agent.runtime.context.safety-margin-tokens=2000",
                        "gitnova.agent.runtime.context.summary-trigger-ratio=0.8",
                        "gitnova.agent.runtime.context.compact-trigger-ratio=0.9",
                        "gitnova.agent.runtime.context.keep-recent-groups=4",
                        "gitnova.agent.runtime.observation.max-inline-tokens=2048",
                        "gitnova.agent.runtime.observation.max-preview-tokens=700")
                .run(application -> {
                    assertNull(application.getStartupFailure());
                    assertNotNull(application.getBean(AgentRuntime.class));
                    assertEquals(new ObservationPolicy(2048, 700), application.getBean(ObservationPolicy.class));
                    var properties = application.getBean(AgentRuntimeProperties.class);
                    assertEquals(new ContextBudget(32000, 2000), properties.context());
                    assertEquals(new ContextBudget.Assessment(26000, 20000, 16000, 0.8),
                            properties.context().assess(22000, 6000,
                                    application.getBean(AgentRuntimePolicy.class).maxOutputTokens()));
                    String id = UUID.randomUUID().toString();
                    RepoKey repo = new RepoKey(1L, 2L);
                    var session = mock(AgentSession.class);
                    when(session.acceptsNewTasks()).thenReturn(true);
                    when(session.repoKey()).thenReturn(repo);
                    when(session.createdByActorId()).thenReturn(1L);
                    when(sessions.require(id)).thenReturn(session);
                    application.getBean(AgentTaskService.class).create(repo, id, 1, "Inspect code", "request-1");
                    var command = ArgumentCaptor.forClass(CreateTaskCommand.class);
                    verify(tasks).createTaskWithInitialRun(command.capture());
                    assertEquals(new ObservationPolicy(2048, 700), command.getValue().executionConfig().observationPolicy());
                    assertEquals(properties.context(), command.getValue().executionConfig().contextBudget());
                    assertTrue(command.getValue().executionConfig().toolSet().enabledDefinitionNames().contains("readArtifact"));
                });
    }

    @Test
    void shouldBindAllFiveContextSettingsThroughTheCanonicalConstructor() {
        var environment = new MockEnvironment()
                .withProperty("gitnova.agent.runtime.observation.max-inline-tokens", "2048")
                .withProperty("gitnova.agent.runtime.observation.max-preview-tokens", "700")
                .withProperty("gitnova.agent.runtime.context.context-window-tokens", "64000")
                .withProperty("gitnova.agent.runtime.context.safety-margin-tokens", "3000")
                .withProperty("gitnova.agent.runtime.context.summary-trigger-ratio", "0.7")
                .withProperty("gitnova.agent.runtime.context.compact-trigger-ratio", "0.95")
                .withProperty("gitnova.agent.runtime.context.keep-recent-groups", "6");

        var properties = Binder.get(environment).bind("gitnova.agent.runtime", AgentRuntimeProperties.class).get();
        assertEquals(new ContextBudget(64000, 3000, 0.7, 0.95, 6), properties.context());
    }

    @Test
    void shouldRejectMissingOrInvalidContextConfiguration() {
        var environment = new MockEnvironment()
                .withProperty("gitnova.agent.runtime.observation.max-inline-tokens", "2048")
                .withProperty("gitnova.agent.runtime.observation.max-preview-tokens", "700");
        assertThrows(BindException.class,
                () -> Binder.get(environment).bind("gitnova.agent.runtime", AgentRuntimeProperties.class));

        environment.withProperty("gitnova.agent.runtime.context.context-window-tokens", "0");
        assertThrows(BindException.class,
                () -> Binder.get(environment).bind("gitnova.agent.runtime", AgentRuntimeProperties.class));

        environment.withProperty("gitnova.agent.runtime.context.context-window-tokens", "32000")
                .withProperty("gitnova.agent.runtime.context.safety-margin-tokens", "32000");
        assertThrows(BindException.class,
                () -> Binder.get(environment).bind("gitnova.agent.runtime", AgentRuntimeProperties.class));
    }
}
