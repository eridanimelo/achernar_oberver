package dev.achernar.observer.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import dev.achernar.observer.model.Trace;
import dev.achernar.observer.repository.TraceRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class ProxyService {

    private final RestClient client;
    private final ObjectMapper objectMapper;
    private final TraceRepository traceRepository;
    private final ProjectResolver projectResolver;
    private final boolean capturePayloads;
    private final boolean captureHeaders;
    private final boolean redactSecrets;

    public ProxyService(
            @Value("${observer.upstream}") String upstream,
            @Value("${observer.capture.payloads:true}") boolean capturePayloads,
            @Value("${observer.capture.headers:true}") boolean captureHeaders,
            @Value("${observer.capture.redact-secrets:true}") boolean redactSecrets,
            ObjectMapper objectMapper,
            TraceRepository traceRepository,
            ProjectResolver projectResolver) {
        this.client = RestClient.builder().baseUrl(upstream).build();
        this.objectMapper = objectMapper;
        this.traceRepository = traceRepository;
        this.projectResolver = projectResolver;
        this.capturePayloads = capturePayloads;
        this.captureHeaders = captureHeaders;
        this.redactSecrets = redactSecrets;
    }

    public ResponseEntity<String> forward(String path, String body, HttpHeaders incomingHeaders) {
        Instant startedAt = Instant.now();
        Trace trace = new Trace();
        trace.setStartedAt(startedAt);
        trace.setKind("llm");
        trace.setName(path);

        try {
            JsonNode request = objectMapper.readTree(body);
            JsonNode metadata = request.path("metadata");

            trace.setModel(text(request, "model"));
            trace.setProject(first(
                    incomingHeaders.getFirst("x-achernar-project"),
                    metadata.path("project").asText(null)));
            trace.setSessionId(headerOr(
                    metadata, "x-achernar-session", incomingHeaders, "session_id"));
            trace.setAgent(headerOr(
                    metadata, "x-achernar-agent", incomingHeaders, "agent"));
            trace.setTraceId(first(
                    incomingHeaders.getFirst("x-achernar-trace"),
                    metadata.path("trace_id").asText(null),
                    UUID.randomUUID().toString()));
            trace.setParentSpanId(first(
                    incomingHeaders.getFirst("x-achernar-parent-span"),
                    metadata.path("parent_span_id").asText(null)));

            if (metadata.isObject()) {
                trace.setMetadata(objectMapper.convertValue(metadata, Map.class));
            }
            if (capturePayloads) {
                trace.setRequestBody(request.toString());
            }
            if (captureHeaders) {
                trace.setRequestHeaders(safeHeaders(incomingHeaders));
            }

            ResponseEntity<String> response = client.post()
                    .uri(path)
                    .headers(headers -> {
                        headers.addAll(incomingHeaders);
                        headers.remove(HttpHeaders.HOST);
                        headers.remove(HttpHeaders.CONTENT_LENGTH);
                    })
                    .body(body)
                    .retrieve()
                    .toEntity(String.class);

            trace.setStatus(response.getStatusCode().value());
            parseUsage(response.getBody(), trace);

            if (capturePayloads) {
                trace.setResponseBody(normalizeJsonString(response.getBody()));
            }
            if (captureHeaders) {
                trace.setResponseHeaders(safeHeaders(response.getHeaders()));
            }

            save(trace, startedAt);
            return ResponseEntity.status(response.getStatusCode())
                    .headers(filterResponseHeaders(response.getHeaders()))
                    .body(response.getBody());
        } catch (Exception exception) {
            trace.setStatus(502);
            trace.setError(exception.getMessage());
            save(trace, startedAt);
            return ResponseEntity.status(502).body("{\"error\":\"Observer upstream error\"}");
        }
    }

    private String normalizeJsonString(String body) {
        if (body == null || body.isBlank()) return body;
        try {
            return objectMapper.readTree(body).toString();
        } catch (Exception exception) {
            return body;
        }
    }

    private Map<String, Object> safeHeaders(HttpHeaders headers) {
        Map<String, Object> safe = new LinkedHashMap<>();
        headers.forEach((key, value) -> safe.put(
                key,
                redactSecrets && isSecret(key) ? List.of("***REDACTED***") : value));
        return safe;
    }

    private boolean isSecret(String key) {
        String normalized = key.toLowerCase();
        return normalized.equals("authorization")
                || normalized.contains("api-key")
                || normalized.contains("token")
                || normalized.equals("cookie")
                || normalized.equals("set-cookie");
    }

    private void parseUsage(String body, Trace trace) {
        try {
            JsonNode usage = objectMapper.readTree(body).path("usage");
            trace.setInputTokens(intOr(usage, "prompt_tokens", "input_tokens"));
            trace.setOutputTokens(intOr(usage, "completion_tokens", "output_tokens"));
            trace.setTotalTokens(intOr(usage, "total_tokens"));

            JsonNode details = usage.path("prompt_tokens_details");
            trace.setCacheReadTokens(intOr(
                    details, "cached_tokens", "cache_read_input_tokens"));
            trace.setCacheWriteTokens(intOr(
                    details, "cache_creation_input_tokens", "cache_write_input_tokens"));
        } catch (Exception ignored) {
            // Some providers do not return usage information.
        }
    }

    private Integer intOr(JsonNode node, String... keys) {
        for (String key : keys) {
            if (node.has(key) && node.get(key).canConvertToInt()) {
                return node.get(key).asInt();
            }
        }
        return null;
    }

    private String text(JsonNode node, String key) {
        return node.has(key) ? node.get(key).asText(null) : null;
    }

    private String headerOr(
            JsonNode metadata,
            String headerName,
            HttpHeaders headers,
            String metadataKey) {
        return first(headers.getFirst(headerName), metadata.path(metadataKey).asText(null));
    }

    private String first(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private void save(Trace trace, Instant startedAt) {
        trace.setProject(projectResolver.resolve(trace));
        trace.setDurationMs(Duration.between(startedAt, Instant.now()).toMillis());
        traceRepository.save(trace);
    }

    private HttpHeaders filterResponseHeaders(HttpHeaders headers) {
        HttpHeaders filtered = new HttpHeaders();
        headers.forEach((key, value) -> {
            if (!key.equalsIgnoreCase("transfer-encoding")
                    && !key.equalsIgnoreCase("content-length")) {
                filtered.put(key, value);
            }
        });
        return filtered;
    }
}
