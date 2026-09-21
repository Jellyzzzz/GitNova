package com.gitnova.service.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gitnova.service.agent.context.TokenEstimator;
import com.gitnova.service.agent.journal.RunJournal;
import com.gitnova.service.agent.tool.ToolExecutionContext;
import com.gitnova.service.agent.tool.ToolResult;
import com.gitnova.service.agent.tool.ToolStatus;
import com.gitnova.storage.artifact.LocalArtifactStore;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Shared read/search implementation for immutable views; not a filesystem or a new storage authority. */
@Component
public final class ArtifactTextReader {
    public static final int MAX_PAGE_TOKENS = 2048;
    private static final int MAX_LINES = 200;
    private static final Pattern PATH = Pattern.compile("artifact://tool-results/([1-9][0-9]{0,18})/([a-z]+\\.(?:txt|patch|json|jsonl))");
    private static final Map<String, String> TEXT_FIELDS = Map.of(
            "stdout.txt", "stdout", "stderr.txt", "stderr", "diff.patch", "unifiedDiff");
    private final LocalArtifactStore store;
    private final RunJournal journal;
    private final ObjectMapper mapper;
    private final TokenEstimator tokens;

    public ArtifactTextReader(LocalArtifactStore store, RunJournal journal, ObjectMapper mapper, TokenEstimator tokens) {
        this.store = store;
        this.journal = journal;
        this.mapper = mapper;
        this.tokens = tokens;
    }

    /** Fixed views only. Array views retain each complete captured entry as one JSON line. */
    public static Map<String, String> views(JsonNode payload) {
        Map<String, String> views = new LinkedHashMap<>();
        views.put("result.json", "");
        for (String view : List.of("stdout.txt", "stderr.txt", "diff.patch")) {
            String field = TEXT_FIELDS.get(view);
            if (payload.path(field).isTextual()) views.put(view, field);
        }
        for (String field : List.of("lines", "matches", "files", "entries", "paths", "hunks")) {
            if (payload.path(field).isArray()) views.put(field + ".jsonl", field);
        }
        return views;
    }

    public ToolResult read(ToolExecutionContext execution, String path, JsonNode args) {
        return execute(execution, path, args, false);
    }

    public ToolResult search(ToolExecutionContext execution, String path, JsonNode args) {
        return execute(execution, path, args, true);
    }

    private ToolResult execute(ToolExecutionContext execution, String path, JsonNode args, boolean search) {
        try {
            var match = PATH.matcher(path);
            if (!match.matches()) throw new IllegalArgumentException("Use an issued artifact://tool-results/<step>/<view> path");
            long sequence = Long.parseLong(match.group(1));
            String view = match.group(2);
            if (args.has("revision")) throw new IllegalArgumentException("revision is not applicable to historical Artifacts");
            boolean cursorPresent = args.has("cursor");
            if (cursorPresent && (!args.path("cursor").isTextual() || args.path("cursor").asText().length() > 256
                    || args.has("startLine") || args.has("endLine"))) {
                throw new IllegalArgumentException("Use cursor alone, not together with a line range");
            }
            if (!search && !cursorPresent && (!args.path("startLine").isIntegralNumber()
                    || !args.path("startLine").canConvertToInt() || !args.path("endLine").isIntegralNumber()
                    || !args.path("endLine").canConvertToInt())) {
                throw new IllegalArgumentException("Supply integer startLine/endLine, or the returned cursor");
            }
            String query = search ? args.path("query").asText() : "";
            if (search && (!args.path("query").isTextual() || query.isEmpty() || query.length() > 4096
                    || !args.path("caseSensitive").isBoolean())) {
                throw new IllegalArgumentException("Supply a nonempty literal query (at most 4096 characters) and caseSensitive");
            }
            String session = execution.agent().sessionId();
            var reference = journal.findArtifactBySource(session, sequence);
            if (reference.isEmpty()) return ToolResult.error(ToolStatus.NOT_FOUND, "ARTIFACT_NOT_FOUND",
                    "No committed Artifact is available at that source step in this Session", false);
            JsonNode original = store.readToolResult(execution.agent(), reference.get());
            JsonNode payload = original.path("payload");
            String field = views(payload).get(view);
            if (field == null) return ToolResult.error(ToolStatus.NOT_FOUND, "ARTIFACT_VIEW_NOT_FOUND",
                    "This captured result does not provide the requested view", false);
            String text;
            if (view.equals("result.json")) text = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(original);
            else if (TEXT_FIELDS.containsKey(view)) text = payload.path(field).textValue();
            else {
                StringBuilder entries = new StringBuilder();
                for (JsonNode entry : payload.path(field)) entries.append(entry).append('\n');
                text = entries.toString();
            }
            List<String> lines = text.lines().toList();
            // The binding detects accidental reuse across resources, Sessions, modes, and queries.
            // It is not authorization: every page still performs the committed Journal lookup above.
            String binding = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                    (session + "\n" + path + "\n" + reference.get().sha256() + "\n" + search + "\n"
                            + query + "\n" + args.path("caseSensitive").asBoolean()).getBytes(StandardCharsets.UTF_8)))
                    .substring(0, 24);
            int line = search ? 1 : args.path("startLine").asInt();
            int end = search ? lines.size() : args.path("endLine").asInt();
            int offset = 0;
            if (cursorPresent) {
                String[] parts = args.path("cursor").textValue().split(":", -1);
                if (parts.length != 4 || !parts[0].equals(binding)) throw new IllegalArgumentException("Cursor does not match this resource/query");
                line = Integer.parseInt(parts[1]);
                offset = Integer.parseInt(parts[2]);
                end = Integer.parseInt(parts[3]);
            }
            if (line < 1 || end < line && !(lines.isEmpty() && end == 0) || offset < 0
                    || !search && (long) end - line + 1 > MAX_LINES
                    || line > lines.size() && !(lines.isEmpty() && line == 1)
                    || search && offset != 0) throw new IllegalArgumentException("Invalid line range or cursor");
            if (line <= lines.size() && (offset > lines.get(line - 1).length()
                    || offset > 0 && offset < lines.get(line - 1).length()
                    && Character.isLowSurrogate(lines.get(line - 1).charAt(offset)))) {
                throw new IllegalArgumentException("Cursor is not at a character boundary");
            }
            boolean captureTruncated = TEXT_FIELDS.containsKey(view) && !view.equals("diff.patch")
                    ? payload.path(field + "Truncated").asBoolean(original.path("truncated").asBoolean())
                    : original.path("truncated").asBoolean();
            ObjectNode page = mapper.createObjectNode().put("filePath", path).put("sourceSessionId", session)
                    .put("sourceStepSequence", sequence).put("historical", true)
                    .put("captureTruncated", captureTruncated).put("totalCapturedLines", lines.size());
            if (search) page.put("query", query).put("caseSensitive", args.path("caseSensitive").asBoolean()).put("returnedMatches", 0);
            var returned = page.putArray(search ? "matches" : "lines");
            Pattern literal = search ? Pattern.compile(Pattern.quote(query), args.path("caseSensitive").asBoolean()
                    ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE) : null;
            int limit = Math.min(end, lines.size());
            while (line <= limit && returned.size() < MAX_LINES) {
                String content = lines.get(line - 1);
                int from = offset;
                int to = content.length();
                if (search) {
                    var found = literal.matcher(content);
                    if (!found.find()) { line++; continue; }
                    // One match per captured line; long matches are snippets, never a fabricated whole line.
                    from = Math.max(0, found.start() - 120);
                    if (from > 0 && Character.isLowSurrogate(content.charAt(from))) from--;
                    to = content.offsetByCodePoints(from, Math.min(512, content.codePointCount(from, content.length())));
                }
                ObjectNode item = returned.addObject().put("lineNumber", line);
                int proposedEnd = to;
                while (true) {
                    item.put("content", content.substring(from, proposedEnd))
                            .put("characterOffset", content.codePointCount(0, from))
                            .put("completeLine", from == 0 && proposedEnd == content.length()
                                    && !(TEXT_FIELDS.containsKey(view) && captureTruncated && line == lines.size()));
                    // Reserve the actual continuation envelope before considering the page to fit.
                    int nextLine = search || proposedEnd == content.length() ? line + 1 : line;
                    int nextOffset = nextLine == line ? proposedEnd : 0;
                    continuation(page, path, binding, nextLine, nextOffset, end, lines.size(), search, query,
                            args.path("caseSensitive").asBoolean());
                    if (search) page.put("returnedMatches", returned.size());
                    if (search && !item.path("completeLine").asBoolean()) item.putObject("readRequest")
                            .put("filePath", path).put("startLine", line).put("endLine", line);
                    if (tokens.estimateText(mapper.valueToTree(ToolResult.success(page)).toString()).tokens() <= MAX_PAGE_TOKENS) break;
                    if (returned.size() > 1) {
                        returned.remove(returned.size() - 1);
                        if (search) page.put("returnedMatches", returned.size());
                        continuation(page, path, binding, line, offset, end, lines.size(), search, query,
                                args.path("caseSensitive").asBoolean());
                        return pageResult(page);
                    }
                    int count = content.codePointCount(from, proposedEnd);
                    if (count <= 1) throw new IllegalArgumentException("Required metadata exceeds the Artifact page budget; shorten query");
                    proposedEnd = content.offsetByCodePoints(from, Math.max(1, count / 2));
                }
                if (!search && proposedEnd < content.length()) return pageResult(page);
                line++;
                offset = 0;
            }
            continuation(page, path, binding, line, offset, end, lines.size(), search, query, args.path("caseSensitive").asBoolean());
            if (search) page.put("returnedMatches", returned.size());
            return pageResult(page);
        } catch (IllegalArgumentException exception) {
            return WorkspaceToolResults.invalid("INVALID_ARTIFACT_ARGUMENTS", exception.getMessage());
        } catch (NoSuchFileException exception) {
            return ToolResult.error(ToolStatus.NOT_FOUND, "ARTIFACT_CONTENT_MISSING", "Captured content is unavailable", false);
        } catch (IOException exception) {
            return ToolResult.error(ToolStatus.INTERNAL_ERROR, "ARTIFACT_READ_FAILED", "Artifact read or integrity validation failed", false);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the JVM", impossible);
        }
    }

    private ToolResult pageResult(ObjectNode page) {
        ToolResult result = ToolResult.success(page);
        if (tokens.estimateText(mapper.valueToTree(result).toString()).tokens() > MAX_PAGE_TOKENS) {
            throw new IllegalArgumentException("Required metadata exceeds the Artifact page budget; shorten query");
        }
        return result;
    }

    private void continuation(ObjectNode page, String path, String binding, int line, int offset, int end,
                              int total, boolean search, String query, boolean caseSensitive) {
        boolean more = line <= total;
        page.put("hasMore", more);
        page.remove("nextRequest");
        if (search) page.put("searchComplete", !more);
        if (more) {
            int nextEnd = search ? total : Math.min(total, line > end ? line + MAX_LINES - 1 : end);
            ObjectNode next = page.putObject("nextRequest").put(search ? "path" : "filePath", path)
                    .put("cursor", binding + ":" + line + ":" + offset + ":" + nextEnd);
            if (search) next.put("query", query).put("caseSensitive", caseSensitive);
        }
    }
}
