package com.gitnova.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gitnova.gitlet.Repository;
import com.gitnova.gitobject.CanonicalGitObjectCodec;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("gateway")
class GitletServicePushTest {

    @Test
    void shouldPushTheCommittedSnapshotThroughNegotiationAndTransfer(
            @TempDir Path tempDirectory
    ) throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        AtomicReference<JsonNode> negotiationPayload = new AtomicReference<>();
        AtomicReference<RecordedRequest> transferRequest = new AtomicReference<>();
        AtomicReference<String> remoteHead = new AtomicReference<>();

        try (MockWebServer server = new MockWebServer()) {
            server.setDispatcher(dispatcher(
                    objectMapper,
                    negotiationPayload,
                    transferRequest,
                    remoteHead
            ));
            server.start();
            GitletService service = new GitletService(
                    tempDirectory.toString(),
                    objectMapper,
                    new CanonicalGitObjectCodec(),
                    new OkHttpClient()
            );
            service.init("local-repo");
            Repository repository = service.getRepository("local-repo");
            Files.writeString(
                    repository.getRepoRoot().toPath().resolve("README.md"),
                    "hello GitNova\n"
            );
            repository.add("README.md");
            service.commit("local-repo", "add README");
            Files.writeString(
                    repository.getRepoRoot().toPath().resolve("README.md"),
                    "uncommitted change\n"
            );

            GitletService.PushResult result = service.push(
                    "local-repo",
                    server.url("/").toString(),
                    42L,
                    "main",
                    "test-token"
            );

            assertFalse(result.alreadyUpToDate());
            assertEquals(3, result.objectsUploaded());
            assertEquals(
                    negotiationPayload.get().path("localHeadSha1").asText(),
                    result.remoteHeadSha1()
            );
            assertEquals(3, negotiationPayload.get().path("localObjects").size());
            RecordedRequest transfer = transferRequest.get();
            assertNotNull(transfer);
            assertEquals("Bearer test-token", transfer.getHeader("Authorization"));
            byte[] multipart = transfer.getBody().readByteArray();
            String readableBody = new String(multipart, StandardCharsets.ISO_8859_1);
            assertTrue(readableBody.contains(result.remoteHeadSha1()));
            assertTrue(readableBody.contains("GNOV"));
            assertTrue(readableBody.contains("hello GitNova"));
            assertFalse(readableBody.contains("uncommitted change"));

            GitletService.PushResult retry = service.push(
                    "local-repo",
                    server.url("/").toString(),
                    42L,
                    "main",
                    "test-token"
            );

            assertTrue(retry.alreadyUpToDate());
            assertEquals(result.remoteHeadSha1(), retry.remoteHeadSha1());
            assertEquals(0, retry.objectsUploaded());
            assertEquals(3, server.getRequestCount());
        }
    }

    private static Dispatcher dispatcher(
            ObjectMapper objectMapper,
            AtomicReference<JsonNode> negotiationPayload,
            AtomicReference<RecordedRequest> transferRequest,
            AtomicReference<String> remoteHead
    ) {
        return new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                if ("/api/repos/42/push/negotiate".equals(request.getPath())) {
                    assertEquals("Bearer test-token", request.getHeader("Authorization"));
                    JsonNode payload;
                    try {
                        payload = objectMapper.readTree(request.getBody().readByteArray());
                    } catch (java.io.IOException exception) {
                        throw new AssertionError("Could not read negotiation request", exception);
                    }
                    negotiationPayload.set(payload);
                    return jsonResponse(negotiationResponse(
                            objectMapper,
                            payload,
                            remoteHead.get()
                    ));
                }
                if ("/api/repos/42/push/transfer".equals(request.getPath())) {
                    transferRequest.set(request);
                    String target = negotiationPayload.get().path("localHeadSha1").asText();
                    remoteHead.set(target);
                    return jsonResponse(transferResponse(objectMapper, target));
                }
                return new MockResponse().setResponseCode(404);
            }
        };
    }

    private static String negotiationResponse(
            ObjectMapper mapper,
            JsonNode payload,
            String remoteHead
    ) {
        var root = mapper.createObjectNode();
        root.put("code", 200);
        root.put("message", "success");
        var data = root.putObject("data");
        if (remoteHead == null) {
            data.putNull("remoteHeadSha1");
            data.set("missingObjects", payload.path("localObjects"));
        } else {
            data.put("remoteHeadSha1", remoteHead);
            data.putArray("missingObjects");
        }
        return root.toString();
    }

    private static String transferResponse(ObjectMapper mapper, String target) {
        var root = mapper.createObjectNode();
        root.put("code", 200);
        root.put("message", "success");
        var data = root.putObject("data");
        data.put("newHeadSha1", target);
        data.put("objectsStored", 3);
        return root.toString();
    }

    private static MockResponse jsonResponse(String body) {
        return new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(body);
    }
}
