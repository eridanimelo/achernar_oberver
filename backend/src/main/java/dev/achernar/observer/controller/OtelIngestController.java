package dev.achernar.observer.controller;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import dev.achernar.observer.model.Trace;
import dev.achernar.observer.repository.TraceRepository;
import dev.achernar.observer.service.ProjectResolver;
import dev.achernar.observer.service.AchernarContextResolver;
import dev.achernar.observer.service.LiveTelemetryService;
import java.util.ArrayList;
import java.util.List;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/otel/v1")
public class OtelIngestController {

    private static final Logger log = LoggerFactory.getLogger(OtelIngestController.class);

    private final TraceRepository repository;
    private final ObjectMapper objectMapper;
    private final ProjectResolver projectResolver;
    private final AchernarContextResolver contextResolver;
    private final LiveTelemetryService liveTelemetry;

    public OtelIngestController(TraceRepository repository, ObjectMapper objectMapper,
                               ProjectResolver projectResolver, AchernarContextResolver contextResolver, LiveTelemetryService liveTelemetry) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.projectResolver = projectResolver;
        this.contextResolver = contextResolver;
        this.liveTelemetry = liveTelemetry;
    }

    @PostMapping(value = "/traces", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> traces(@RequestBody JsonNode payload) {
        JsonNode resourceSpans = payload.path("resourceSpans");
        if (resourceSpans.isArray()) {
            for (JsonNode resourceSpan : resourceSpans) {
                Map<String, Object> resourceAttributes = attributes(resourceSpan.path("resource").path("attributes"));
                List<Trace> incoming = new ArrayList<>();
                for (JsonNode scopeSpan : resourceSpan.path("scopeSpans")) {
                    for (JsonNode span : scopeSpan.path("spans")) {
                        incoming.add(toTrace(span, resourceAttributes));
                    }
                }
                // Exporters can deliver HTTP/auth before the LLM span. Resolve the
                // project across this batch before persisting any span.
                Map<String, String> projectsByTrace = new HashMap<>();
                incoming.stream().filter(trace -> trace.getTraceId() != null
                        && trace.getProject() != null && !trace.getProject().isBlank())
                        .forEach(trace -> projectsByTrace.putIfAbsent(trace.getTraceId(), trace.getProject()));
                for (Trace trace : incoming) {
                    if ((trace.getProject() == null || "UNKNOWN".equals(trace.getProject()))
                            && trace.getTraceId() != null) {
                        trace.setProject(projectsByTrace.get(trace.getTraceId()));
                    }
                    trace.setProject(projectResolver.resolve(trace));
                    // Persist each span independently. A malformed provider span must not
                    // make the Collector discard the complete OTLP batch.
                    boolean persisted = false;
                    try {
                        repository.saveAndFlush(trace);
                        persisted = true;
                    } catch (RuntimeException error) {
                        log.warn("Discarding invalid OTEL span id={} traceId={} name={}: {}",
                                trace.getId(), trace.getTraceId(), trace.getName(), error.getMessage());
                    }
                    // Telemetria live nunca pode derrubar a ingestão: publish() não lança,
                    // mas o try extra garante que nem um bug futuro vire "span inválido".
                    if (persisted && "llm".equals(trace.getKind())) {
                        try {
                            liveTelemetry.publish("llm");
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
        }
        return ResponseEntity.ok(Map.of());
    }

    @PostMapping(value = "/metrics", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> metrics(@RequestBody JsonNode payload) {
        return ResponseEntity.ok(Map.of());
    }

    @PostMapping(value = "/logs", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> logs(@RequestBody JsonNode payload) {
        return ResponseEntity.ok(Map.of());
    }

    private Trace toTrace(JsonNode span, Map<String, Object> resourceAttributes) {
        Map<String, Object> spanAttributes = attributes(span.path("attributes"));
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("resource", resourceAttributes);
        metadata.put("attributes", spanAttributes);
        if (span.has("events")) {
            metadata.put("events", objectMapper.convertValue(span.path("events"), Object.class));
        }

        Trace trace = new Trace();
        trace.setId(text(span, "spanId", null));
        trace.setTraceId(text(span, "traceId", null));
        trace.setParentSpanId(text(span, "parentSpanId", null));
        trace.setName(text(span, "name", "llm"));
        trace.setKind(spanAttributes.containsKey("gen_ai.operation.name")
                || spanAttributes.containsKey("gen_ai.usage.input_tokens") ? "llm"
                : trace.getName().startsWith("auth ") ? "auth"
                : spanAttributes.containsKey("http.method") || spanAttributes.containsKey("http.request.method") ? "http" : "span");
        trace.setStartedAt(nanosToInstant(text(span, "startTimeUnixNano", null)));
        trace.setDurationMs(durationMs(span));
        trace.setModel(firstString(spanAttributes,
                "gen_ai.request.model",
                "gen_ai.response.model",
                "llm.request.model",
                "model"));
        String project = firstString(spanAttributes,
                "achernar.project", "metadata.achernar.project", "project", "metadata.project");
        String projectSource = project != null ? "explicit ACHERNAR metadata" : null;
        if (project == null) project = firstString(resourceAttributes,
                "achernar.project", "metadata.achernar.project", "project", "metadata.project");
        if (project != null && projectSource == null) projectSource = "OTEL metadata";
        AchernarContextResolver.ProjectInfo contextProject = null;
        if (project == null) {
            // Fallback: PROJECT.yaml content already present in telemetry (span
            // attributes and/or OTEL events share the same centralized resolver).
            // Never overrides an explicit project.
            contextProject = contextResolver.resolve(
                    spanAttributes.get("gen_ai.input.messages"), span.path("events"));
            if (contextProject != null) {
                project = contextProject.name();
                projectSource = "PROJECT.yaml context";
            }
        }
        trace.setProject(project);
        if (log.isDebugEnabled()) {
            log.debug("Project {}: {}",
                    project != null ? "resolved from " + projectSource : "unresolved, deferring to correlation/default",
                    project != null && project.length() > 100 ? project.substring(0, 100) : project);
        }
        trace.setSessionId(firstString(spanAttributes,
                "session.id",
                "gen_ai.conversation.id",
                "litellm.session_id"));
        trace.setAgent(firstString(spanAttributes,
                "achernar.agent",
                "agent",
                "metadata.agent"));
        trace.setInputTokens(firstInteger(spanAttributes,
                "gen_ai.usage.input_tokens",
                "llm.usage.prompt_tokens",
                "prompt_tokens"));
        trace.setOutputTokens(firstInteger(spanAttributes,
                "gen_ai.usage.output_tokens",
                "llm.usage.completion_tokens",
                "completion_tokens"));
        trace.setCacheReadTokens(firstInteger(spanAttributes,
                "gen_ai.usage.cache_read.input_tokens",
                "gen_ai.usage.cache_read_input_tokens",
                "cache_read_input_tokens",
                "cache_read_tokens"));
        trace.setCacheWriteTokens(firstInteger(spanAttributes,
                "gen_ai.usage.cache_creation.input_tokens",
                "gen_ai.usage.cache_creation_input_tokens",
                "cache_creation_input_tokens",
                "cache_write_tokens"));
        Integer reportedTotal = firstInteger(spanAttributes, "gen_ai.usage.total_tokens");
        trace.setTotalTokens(reportedTotal != null ? reportedTotal : total(trace.getInputTokens(), trace.getOutputTokens()));
        trace.setStatus(status(span));
        trace.setError(error(span));
        // Optional PROJECT.yaml extras ride along in metadata (no prompt persisted,
        // no domain migration: Trace.project stays the single grouping key).
        if (contextProject != null) {
            if (contextProject.description() != null) metadata.put("achernar.project.description", contextProject.description());
            if (contextProject.version() != null) metadata.put("achernar.project.version", contextProject.version());
            if (contextProject.organization() != null) metadata.put("achernar.project.organization", contextProject.organization());
        }
        trace.setMetadata(metadata);
        if (trace.getKind().equals("llm")) {
            trace.setRequestBody(extractContent(span, spanAttributes, true));
            trace.setResponseBody(extractContent(span, spanAttributes, false));
        }
        return trace;
    }

    private Map<String, Object> attributes(JsonNode attributesNode) {
        Map<String, Object> result = new HashMap<>();
        if (!attributesNode.isArray()) {
            return result;
        }
        for (JsonNode attribute : attributesNode) {
            String key = attribute.path("key").asString();
            JsonNode value = attribute.path("value");
            result.put(key, anyValue(value));
        }
        return result;
    }

    private Object anyValue(JsonNode value) {
        if (value.has("stringValue")) return value.get("stringValue").asString();
        if (value.has("intValue")) return parseLong(value.get("intValue").asString());
        if (value.has("doubleValue")) return value.get("doubleValue").asDouble();
        if (value.has("boolValue")) return value.get("boolValue").asBoolean();
        if (value.has("bytesValue")) return value.get("bytesValue").asString();
        if (value.has("arrayValue")) return objectMapper.convertValue(value.get("arrayValue"), Object.class);
        if (value.has("kvlistValue")) return objectMapper.convertValue(value.get("kvlistValue"), Object.class);
        return objectMapper.convertValue(value, Object.class);
    }

    private String extractContent(JsonNode span, Map<String, Object> attributes, boolean request) {
        String[] keys = request
                ? new String[]{"gen_ai.input.messages", "gen_ai.prompt", "llm.prompts", "input"}
                : new String[]{"gen_ai.output.messages", "gen_ai.completion", "llm.completions", "output"};
        for (String key : keys) {
            if (attributes.containsKey(key)) {
                Object raw = attributes.get(key);
                return normalizePayload(raw);
            }
        }
        for (JsonNode event : span.path("events")) {
            String name = event.path("name").asString("").toLowerCase();
            if ((request && (name.contains("input") || name.contains("prompt")))
                    || (!request && (name.contains("output") || name.contains("completion") || name.contains("response")))) {
                return event.toString();
            }
        }
        return null;
    }

    private String normalizePayload(Object value) {
        if (value == null) return null;
        if (value instanceof String stringValue) {
            if (stringValue.isBlank()) return stringValue;
            try { return objectMapper.readTree(stringValue).toString(); }
            catch (Exception ignored) { return stringValue; }
        }
        try { return objectMapper.valueToTree(value).toString(); }
        catch (Exception exception) { return String.valueOf(value); }
    }

    private long durationMs(JsonNode span) {
        long start = parseLong(text(span, "startTimeUnixNano", "0"));
        long end = parseLong(text(span, "endTimeUnixNano", "0"));
        return start > 0 && end >= start ? (end - start) / 1_000_000L : 0L;
    }

    private Instant nanosToInstant(String value) {
        long nanos = parseLong(value);
        if (nanos <= 0) return Instant.now();
        return Instant.ofEpochSecond(nanos / 1_000_000_000L, nanos % 1_000_000_000L);
    }

    private int status(JsonNode span) {
        int code = span.path("status").path("code").asInt(0);
        return code == 2 ? 500 : 200;
    }

    private String error(JsonNode span) {
        JsonNode status = span.path("status");
        if (status.path("code").asInt(0) == 2) {
            return status.path("message").asString("OpenTelemetry span reported an error");
        }
        return null;
    }

    private String firstString(Map<String, Object> values, String... keys) {
        for (String key : keys) {
            Object value = values.get(key);
            if (value != null && !value.toString().isBlank()) return value.toString();
        }
        return null;
    }

    private Integer firstInteger(Map<String, Object> values, String... keys) {
        for (String key : keys) {
            Object value = values.get(key);
            if (value instanceof Number number) return number.intValue();
            if (value != null) {
                try { return Integer.valueOf(value.toString()); } catch (NumberFormatException ignored) { }
            }
        }
        return null;
    }

    private Integer total(Integer input, Integer output) {
        if (input == null && output == null) return null;
        return (input == null ? 0 : input) + (output == null ? 0 : output);
    }

    private String text(JsonNode node, String field, String defaultValue) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? defaultValue : value.asString();
    }

    private long parseLong(String value) {
        if (value == null || value.isBlank()) return 0L;
        try { return Long.parseLong(value); } catch (NumberFormatException ignored) { return 0L; }
    }
}
