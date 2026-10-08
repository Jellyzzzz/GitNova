package com.gitnova.agent.protocol.json;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gitnova.agent.protocol.command.AgentCommand;
import com.gitnova.agent.protocol.command.CommandType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

class ProtocolJsonTest {
    private final ObjectMapper mapper = ProtocolJson.mapper();

    @ParameterizedTest
    @CsvSource({
            "initialize, INITIALIZE, Initialize",
            "submit_task, SUBMIT_TASK, Submit",
            "steer_task, STEER_TASK, Steer",
            "cancel_task, CANCEL_TASK, Cancel",
            "preview_changes, PREVIEW_CHANGES, Preview",
            "checkpoint_session, CHECKPOINT_SESSION, Checkpoint",
            "ack_checkpoint, ACK_CHECKPOINT, CheckpointAck",
            "stop_worker, STOP_WORKER, StopWorker"
    })
    void allCommandsShouldRoundTrip(String name, CommandType type, String payloadClass) throws Exception {
        ObjectNode source = fixture(name);
        byte[] body = mapper.writeValueAsBytes(source);

        AgentCommand command = ProtocolJson.readCommand(body);
        byte[] encoded = ProtocolJson.canonicalBytes(command);
        AgentCommand decoded = ProtocolJson.readCommand(encoded);

        assertEquals(type, command.type());
        assertEquals(payloadClass, command.payload().getClass().getSimpleName());
        assertEquals(1L, command.runnerEpoch());
        assertEquals(command, decoded);
        assertEquals(source, mapper.readTree(encoded));
        assertEquals(ProtocolJson.sha256(command), ProtocolJson.sha256(decoded));
    }

    @Test
    void shouldSortNestedObjectsWithoutChangingArraysOrTheSource() throws Exception {
        JsonNode source = mapper.readTree("{\"z\":[{\"b\":2,\"a\":1},3,1,null],\"a\":true}");
        String original = source.toString();

        byte[] encoded = ProtocolJson.canonicalBytes(source);

        assertEquals("{\"a\":true,\"z\":[{\"a\":1,\"b\":2},3,1,null]}", new String(encoded, UTF_8));
        assertEquals(original, source.toString());
        assertNotEquals(ProtocolJson.sha256(List.of(1, 2)), ProtocolJson.sha256(List.of(2, 1)));
    }

    @Test
    void shouldUseUnsignedUtf8KeyOrder() throws Exception {
        ObjectNode source = mapper.createObjectNode();
        source.put("\uD800\uDC00", 2);
        source.put("\uE000", 1);

        byte[] encoded = ProtocolJson.canonicalBytes(source);
        JsonNode decoded = mapper.readTree(encoded);
        Iterator<String> names = decoded.fieldNames();

        assertEquals("\uE000", names.next());
        assertEquals("\uD800\uDC00", names.next());
    }

    @Test
    void shouldKeepStableBytesAndTheKnownSha256() throws Exception {
        JsonNode first = mapper.readTree("{\"z\":2,\"a\":1}");
        JsonNode second = mapper.readTree("{\"a\":1,\"z\":2}");

        assertArrayEquals(ProtocolJson.canonicalBytes(first), ProtocolJson.canonicalBytes(second));
        assertEquals("{\"a\":1,\"z\":2}", new String(ProtocolJson.canonicalBytes(first), UTF_8));
        assertEquals("99168216144c7fed5d4c54916cf98d9c66096280c04a499822a99b6658bd177a",
                ProtocolJson.sha256(first));
        assertNotEquals(ProtocolJson.sha256("{\"a\":1,\"z\":2}"), ProtocolJson.sha256(first));
    }

    @Test
    void shouldPreserveUserTextAndNotCheckExpirationWhileDecoding() throws Exception {
        ObjectNode source = fixture("submit_task");
        ObjectNode payload = (ObjectNode) source.get("payload");
        String message = "  中文\n\"保留原文\" e\u0301  ";
        payload.put("message", message);
        payload.put("deadlineAt", "2000-01-01T00:00:00Z");

        AgentCommand command = ProtocolJson.readCommand(mapper.writeValueAsBytes(source));
        AgentCommand.Submit submit = assertInstanceOf(AgentCommand.Submit.class, command.payload());

        assertEquals(message, submit.message());
        assertEquals("2000-01-01T00:00:00Z", submit.deadlineAt().toString());
    }

    @Test
    void mapperCopiesShouldNotChangeInternalEncoding() throws Exception {
        AgentCommand command = ProtocolJson.readCommand(mapper.writeValueAsBytes(fixture("submit_task")));
        byte[] original = ProtocolJson.canonicalBytes(command);
        ObjectMapper copy = ProtocolJson.mapper();
        copy.enable(SerializationFeature.INDENT_OUTPUT);
        copy.enable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        assertNotSame(mapper, copy);
        assertArrayEquals(original, ProtocolJson.canonicalBytes(command));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   ", "null", "[]", "true", "1", "\"command\""})
    void shouldRejectAnAbsentOrNonObjectBody(String body) {
        byte[] bytes = body == null ? null : body.getBytes(UTF_8);
        assertThrows(IOException.class, () -> ProtocolJson.readCommand(bytes));
    }

    @ParameterizedTest
    @ValueSource(strings = {"schemaVersion", "commandId", "sessionId", "worklineId",
            "runnerEpoch", "type", "taskId", "attemptId", "payload"})
    void shouldRejectEveryMissingEnvelopeField(String field) throws Exception {
        ObjectNode source = fixture("submit_task");
        source.remove(field);
        byte[] body = mapper.writeValueAsBytes(source);

        IOException error = assertThrows(IOException.class, () -> ProtocolJson.readCommand(body));
        assertTrue(error.getMessage().contains(field));
    }

    @ParameterizedTest
    @ValueSource(strings = {"schemaVersion", "commandId", "sessionId", "worklineId",
            "runnerEpoch", "type", "payload"})
    void shouldRejectNullForNonNullableEnvelopeFields(String field) throws Exception {
        ObjectNode source = fixture("submit_task");
        source.putNull(field);
        byte[] body = mapper.writeValueAsBytes(source);

        assertThrows(IOException.class, () -> ProtocolJson.readCommand(body));
    }

    @ParameterizedTest
    @ValueSource(strings = {"commandId", "sessionId", "worklineId", "taskId", "attemptId", "type"})
    void shouldRejectNonStringIdentityAndTypeFields(String field) throws Exception {
        ObjectNode source = fixture("submit_task");
        source.put(field, 123);
        byte[] body = mapper.writeValueAsBytes(source);

        assertThrows(IOException.class, () -> ProtocolJson.readCommand(body));
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"1\"", "1.5", "true", "9223372036854775808"})
    void shouldRejectInvalidEpochTypesAndOverflow(String value) throws Exception {
        ObjectNode source = fixture("submit_task");
        source.set("runnerEpoch", mapper.readTree(value));
        byte[] body = mapper.writeValueAsBytes(source);

        assertThrows(IOException.class, () -> ProtocolJson.readCommand(body));
    }

    @Test
    void shouldAcceptAnEpochBeyondIntegerRange() throws Exception {
        ObjectNode source = fixture("submit_task");
        source.put("runnerEpoch", 2147483648L);

        AgentCommand command = ProtocolJson.readCommand(mapper.writeValueAsBytes(source));

        assertEquals(2147483648L, command.runnerEpoch());
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"2\"", "2.5", "true", "2147483648"})
    void shouldRejectInvalidSchemaTypesAndOverflow(String value) throws Exception {
        ObjectNode source = fixture("submit_task");
        source.set("schemaVersion", mapper.readTree(value));
        byte[] body = mapper.writeValueAsBytes(source);

        assertThrows(IOException.class, () -> ProtocolJson.readCommand(body));
    }

    @Test
    void shouldRejectUnknownFieldsAndPayloadTypeMismatch() throws Exception {
        for (String field : List.of("extra", "@class", "attemptedId")) {
            ObjectNode source = fixture("submit_task");
            source.put(field, "not-allowed");
            byte[] body = mapper.writeValueAsBytes(source);
            assertThrows(IOException.class, () -> ProtocolJson.readCommand(body));
        }
        for (String field : List.of("mode", "allowedTools", "@class")) {
            ObjectNode source = fixture("submit_task");
            ObjectNode payload = (ObjectNode) source.get("payload");
            payload.put(field, "not-allowed");
            byte[] body = mapper.writeValueAsBytes(source);
            assertThrows(IOException.class, () -> ProtocolJson.readCommand(body));
        }
        ObjectNode mismatched = fixture("submit_task");
        mismatched.put("type", "CANCEL_TASK");
        byte[] body = mapper.writeValueAsBytes(mismatched);
        assertThrows(IOException.class, () -> ProtocolJson.readCommand(body));
    }

    @ParameterizedTest
    @ValueSource(strings = {"message", "expectedPublishedHead", "deadlineAt", "runtimeConfigDigest", "worklineStatus"})
    void shouldRejectMissingNullAndNonStringSubmitFields(String field) throws Exception {
        ObjectNode source = fixture("submit_task");
        ObjectNode payload = (ObjectNode) source.get("payload");
        payload.remove(field);
        byte[] missing = mapper.writeValueAsBytes(source);
        assertThrows(IOException.class, () -> ProtocolJson.readCommand(missing));

        payload.putNull(field);
        byte[] nullValue = mapper.writeValueAsBytes(source);
        assertThrows(IOException.class, () -> ProtocolJson.readCommand(nullValue));

        payload.put(field, 123);
        byte[] wrongType = mapper.writeValueAsBytes(source);
        assertThrows(IOException.class, () -> ProtocolJson.readCommand(wrongType));
    }

    @Test
    void shouldRejectMissingOrInvalidCheckpointSequence() throws Exception {
        ObjectNode source = fixture("ack_checkpoint");
        ObjectNode payload = (ObjectNode) source.get("payload");
        payload.remove("coveredThrough");
        byte[] missing = mapper.writeValueAsBytes(source);
        assertThrows(IOException.class, () -> ProtocolJson.readCommand(missing));

        for (String value : List.of("null", "\"42\"", "1.5", "9223372036854775808", "-1")) {
            payload.set("coveredThrough", mapper.readTree(value));
            byte[] body = mapper.writeValueAsBytes(source);
            assertThrows(IOException.class, () -> ProtocolJson.readCommand(body));
        }
    }

    @Test
    void shouldRejectDuplicatesAndTrailingJson() throws Exception {
        String source = mapper.writeValueAsString(fixture("submit_task"));
        List<String> invalid = List.of(
                source.replace("\"type\":", "\"type\":\"SUBMIT_TASK\",\"type\":"),
                source.replace("\"message\":", "\"message\":\"duplicate\",\"message\":"),
                source + "{}",
                source + " trailing"
        );
        for (String input : invalid) {
            byte[] body = input.getBytes(UTF_8);
            assertThrows(IOException.class, () -> ProtocolJson.readCommand(body));
        }
    }

    @Test
    void shouldRejectMalformedUtf8AndExcessiveNesting() {
        byte[] invalidUtf8 = {'{', '"', (byte) 0xC3, '"', ':', '1', '}'};
        assertThrows(IOException.class, () -> ProtocolJson.readCommand(invalidUtf8));

        int excessiveDepth = mapper.getFactory().streamReadConstraints().getMaxNestingDepth() + 1;
        String nested = "{\"value\":" + "[".repeat(excessiveDepth) + "0" + "]".repeat(excessiveDepth) + "}";
        byte[] body = nested.getBytes(UTF_8);
        assertThrows(IOException.class, () -> ProtocolJson.readCommand(body));
    }

    @ParameterizedTest
    @ValueSource(strings = {"commandId", "sessionId", "worklineId", "taskId", "attemptId"})
    void shouldRejectMalformedIds(String field) throws Exception {
        ObjectNode source = fixture("submit_task");
        source.put(field, "invalid-id");
        byte[] body = mapper.writeValueAsBytes(source);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> ProtocolJson.readCommand(body));
        assertTrue(error.getMessage().contains(field));
    }

    @ParameterizedTest
    @ValueSource(strings = {"taskId", "attemptId"})
    void shouldEnforceTaskIdentityNullability(String field) throws Exception {
        ObjectNode submit = fixture("submit_task");
        submit.putNull(field);
        byte[] missingTaskIdentity = mapper.writeValueAsBytes(submit);
        assertThrows(IllegalArgumentException.class, () -> ProtocolJson.readCommand(missingTaskIdentity));

        ObjectNode initialize = fixture("initialize");
        initialize.put(field, "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        byte[] unexpectedTaskIdentity = mapper.writeValueAsBytes(initialize);
        assertThrows(IllegalArgumentException.class, () -> ProtocolJson.readCommand(unexpectedTaskIdentity));

        initialize.putNull(field);
        byte[] allowedNull = mapper.writeValueAsBytes(initialize);
        AgentCommand command = ProtocolJson.readCommand(allowedNull);
        assertNull(command.taskId());
        assertNull(command.attemptId());

        initialize.remove(field);
        byte[] missingKey = mapper.writeValueAsBytes(initialize);
        assertThrows(IOException.class, () -> ProtocolJson.readCommand(missingKey));
    }

    @Test
    void shouldRejectUnsupportedVersionsTypesAndNonPositiveEpochs() throws Exception {
        ObjectNode source = fixture("submit_task");
        source.put("schemaVersion", 1);
        byte[] oldVersion = mapper.writeValueAsBytes(source);
        assertThrows(IllegalArgumentException.class, () -> ProtocolJson.readCommand(oldVersion));
        source.put("schemaVersion", 2);
        for (long epoch : new long[]{0L, -1L}) {
            source.put("runnerEpoch", epoch);
            byte[] body = mapper.writeValueAsBytes(source);
            assertThrows(IllegalArgumentException.class, () -> ProtocolJson.readCommand(body));
        }
        source.put("runnerEpoch", 1);
        source.put("type", "UNKNOWN");
        byte[] unknownType = mapper.writeValueAsBytes(source);
        assertThrows(IOException.class, () -> ProtocolJson.readCommand(unknownType));
    }

    @Test
    void shouldUseTheContractWorklineStatus() throws Exception {
        ObjectNode source = fixture("submit_task");
        ObjectNode payload = (ObjectNode) source.get("payload");
        for (String status : List.of("ACTIVE", "MERGED", "SOURCE_MISSING")) {
            payload.put("worklineStatus", status);
            AgentCommand command = ProtocolJson.readCommand(mapper.writeValueAsBytes(source));
            AgentCommand.Submit submit = assertInstanceOf(AgentCommand.Submit.class, command.payload());
            assertEquals(status, submit.worklineStatus());
        }
        payload.put("worklineStatus", "SOURCE_MISSED");
        byte[] invalid = mapper.writeValueAsBytes(source);
        assertThrows(IOException.class, () -> ProtocolJson.readCommand(invalid));
    }

    @Test
    void directConstructionShouldStillRejectMismatchedPayloadAndMissingIdentity() throws Exception {
        AgentCommand command = ProtocolJson.readCommand(mapper.writeValueAsBytes(fixture("submit_task")));
        assertThrows(IllegalArgumentException.class, () -> new AgentCommand(
                2, command.commandId(), command.sessionId(), command.worklineId(), 1L,
                CommandType.CANCEL_TASK, command.taskId(), command.attemptId(), command.payload()));
        assertThrows(IllegalArgumentException.class, () -> new AgentCommand(
                2, null, command.sessionId(), command.worklineId(), 1L,
                command.type(), command.taskId(), command.attemptId(), command.payload()));
        assertThrows(IllegalArgumentException.class, () -> new AgentCommand(
                2, command.commandId(), command.sessionId(), command.worklineId(), 1L,
                command.type(), command.taskId(), command.attemptId(), null));
    }

    @Test
    void shouldNotHideEncodingFailures() {
        IOException error = assertThrows(IOException.class, () -> ProtocolJson.canonicalBytes(new BrokenValue()));
        assertNotNull(error.getCause());
    }

    private ObjectNode fixture(String name) throws IOException {
        try (InputStream input = getClass().getResourceAsStream("/protocol/" + name + ".json")) {
            assertNotNull(input, "Missing fixture: " + name);
            return (ObjectNode) mapper.readTree(input);
        }
    }

    public static class BrokenValue {
        public String getValue() {
            throw new IllegalStateException("Deliberate test encoding failure");
        }
    }
}
