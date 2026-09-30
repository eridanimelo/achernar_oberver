package dev.achernar.observer.controller;

import dev.achernar.observer.model.Trace;
import dev.achernar.observer.dto.TracePageResponse;

import dev.achernar.observer.repository.TraceRepository;
import dev.achernar.observer.service.ProjectResolver;
import dev.achernar.observer.service.OpenCodeIngestService;
import tools.jackson.databind.JsonNode;
import dev.achernar.observer.service.ProxyService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import java.util.UUID;
import java.util.function.Function;

import org.springframework.http.HttpHeaders;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import dev.achernar.observer.service.LiveTelemetryService;

@RestController
public class ObserverController {

    private final ProxyService proxyService;
    private final TraceRepository traceRepository;
    private final ProjectResolver projectResolver;
    private final LiveTelemetryService liveTelemetry;
    private final OpenCodeIngestService openCodeIngestService;

    @Value("${app.version:unknown}")
    private String appVersion;

    public ObserverController(
            ProxyService proxyService,
            TraceRepository traceRepository,
            ProjectResolver projectResolver,
            LiveTelemetryService liveTelemetry,
            OpenCodeIngestService openCodeIngestService) {
        this.proxyService = proxyService;
        this.traceRepository = traceRepository;
        this.projectResolver = projectResolver;
        this.liveTelemetry = liveTelemetry;
        this.openCodeIngestService = openCodeIngestService;
    }

    @PostMapping(
            value = {"/v1/chat/completions", "/v1/responses"},
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> proxy(
            @RequestBody String body,
            @RequestHeader HttpHeaders headers,
            HttpServletRequest request) {
        return proxyService.forward(request.getRequestURI(), body, headers);
    }

    @PostMapping("/api/events")
    public Trace event(@RequestBody Trace event) {
        if (event.getStartedAt() == null) {
            event.setStartedAt(Instant.now());
        }
        if (event.getTraceId() == null) {
            event.setTraceId(UUID.randomUUID().toString());
        }

        event.setProject(projectResolver.resolve(event));
        Trace saved = traceRepository.save(event);
        liveTelemetry.publish("event");
        return saved;
    }

    @PostMapping(value = "/api/opencode/events", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> openCodeEvent(@RequestBody JsonNode event) {
        Trace saved = openCodeIngestService.ingest(event);
        return saved == null ? ResponseEntity.accepted().build() : ResponseEntity.ok(saved);
    }

    @GetMapping(value = "/api/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        return liveTelemetry.subscribe();
    }

    @GetMapping("/api/version")
    public Map<String, String> version() {
        return Map.of("name", "achernar-observer-backend", "version", appVersion);
    }

    @DeleteMapping("/api/traces")
    public ResponseEntity<Void> clearTraces() {
        traceRepository.deleteAllInBatch();
        liveTelemetry.publish("cleared");
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/traces")
    public List<Trace> traces(
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) String project) {
        return filtered(from, to, project);
    }

    @GetMapping("/api/traces/page")
    public TracePageResponse tracePage(
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) String project,
            @RequestParam(defaultValue = "false") boolean technical,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        int safeSize = Math.max(10, Math.min(size, 200));
        String normalizedProject = normalizeFilter(project);
        String normalizedSearch = normalizeFilter(search);
        return TracePageResponse.from(traceRepository.findSummaryPage(
                from, to, normalizedProject, technical, normalizedSearch,
                PageRequest.of(Math.max(0, page), safeSize, Sort.by(Sort.Direction.DESC, "startedAt"))));
    }

    @GetMapping("/api/spans/{id}")
    public ResponseEntity<Trace> span(@PathVariable String id) {
        return traceRepository.findById(id)
                .map(trace -> {
                    // Linhas OpenCode antigas foram salvas sem requestBody e com
                    // input sem o cache: agrega os irmãos da sessão na leitura e
                    // persiste, então o detalhe (Fluxo/Conversa/Tools) se cura
                    // sozinho ao abrir e a lista converge em seguida.
                    try {
                        if (trace.getSessionId() != null
                                && (trace.getRequestBody() == null || trace.getResponseBody() == null
                                        || needsInputNormalization(trace))) {
                            String assistantMessageId = assistantMessageIdFrom(trace);
                            if (openCodeIngestService.enrichFromSession(trace, assistantMessageId)) {
                                trace.setProject(projectResolver.resolve(trace));
                                return ResponseEntity.ok(traceRepository.save(trace));
                            }
                        }
                    } catch (RuntimeException ignored) {
                    }
                    return ResponseEntity.ok(trace);
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private boolean needsInputNormalization(Trace trace) {
        return "llm".equals(trace.getKind())
                && trace.getCacheReadTokens() != null && trace.getCacheReadTokens() > 0
                && (trace.getInputTokens() == null || trace.getInputTokens() < trace.getCacheReadTokens());
    }

    @SuppressWarnings("unchecked")
    private String assistantMessageIdFrom(Trace trace) {
        if (trace.getMetadata() == null) return null;
        Object raw = trace.getMetadata().get("raw");
        if (!(raw instanceof Map<?, ?> rawMap)) return null;
        Object data = rawMap.get("data");
        if (!(data instanceof Map<?, ?> dataMap)) return null;
        Object value = ((Map<String, Object>) dataMap).get("assistantMessageID");
        if (value == null) value = ((Map<String, Object>) dataMap).get("assistantMessageId");
        return value == null ? null : String.valueOf(value);
    }

    @GetMapping("/api/traces/{traceId}")
    public List<Trace> trace(@PathVariable String traceId) {
        return traceRepository.findByTraceIdOrderByStartedAtAsc(traceId);
    }

    @GetMapping("/api/sessions/{sessionId}/traces")
    public List<Trace> sessionTraces(@PathVariable String sessionId) {
        return traceRepository.findBySessionIdOrderByStartedAtAsc(sessionId);
    }

    @GetMapping("/api/summary")
    public Map<String, Object> summary(
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) String project) {
        String normalizedProject = normalizeFilter(project);
        List<Object[]> rows = traceRepository.aggregate(from, to, normalizedProject);
        Object[] row = rows.isEmpty() ? null : rows.getFirst();
        long spans = number(row, 0);
        long total = number(row, 1);
        long input = number(row, 2);
        long output = number(row, 3);
        long cacheRead = number(row, 4);
        long cacheWrite = number(row, 5);
        long sessions = number(row, 6);
        long traceCount = number(row, 7);
        long calls = number(row, 8);
        long avgDuration = number(row, 9);
        long agents = number(row, 10);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("spans", spans);
        summary.put("tokens", total);
        summary.put("inputTokens", input);
        summary.put("outputTokens", output);
        summary.put("cacheReadTokens", cacheRead);
        summary.put("cacheWriteTokens", cacheWrite);
        summary.put("traces", traceCount);
        summary.put("sessions", sessions);
        summary.put("llmCalls", calls);
        summary.put("tokensPerCall", calls == 0 ? 0 : Math.round((double) total / calls));
        summary.put("tokensPerSession", sessions == 0 ? 0 : Math.round((double) total / sessions));
        summary.put("avgDurationMs", avgDuration);
        summary.put("agents", agents);
        return summary;
    }

    @GetMapping("/api/projects")
    public List<Map<String, Object>> projectSummary(
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to) {
        return traceRepository.projectAggregates(from, to).stream().map(row -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("project", String.valueOf(row[0]));
            item.put("tokens", number(row, 1));
            item.put("inputTokens", number(row, 2));
            item.put("outputTokens", number(row, 3));
            item.put("cacheReadTokens", number(row, 4));
            item.put("cacheWriteTokens", number(row, 5));
            item.put("sessions", number(row, 6));
            item.put("llmCalls", number(row, 7));
            return item;
        }).toList();
    }

    @GetMapping("/api/timeseries")
    public List<Map<String, Object>> timeseries(
            @RequestParam Instant from,
            @RequestParam Instant to,
            @RequestParam(defaultValue = "hour") String bucket,
            @RequestParam(required = false) String project) {
        String safeBucket = switch (bucket) {
            case "minute", "day", "month" -> bucket;
            default -> "hour";
        };
        String normalizedProject = normalizeFilter(project);
        return traceRepository.timeSeries(from, to, normalizedProject, safeBucket).stream().map(row -> {
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("timestamp", row[0]);
            point.put("tokens", number(row, 1));
            point.put("inputTokens", number(row, 2));
            point.put("outputTokens", number(row, 3));
            point.put("cacheReadTokens", number(row, 4));
            point.put("cacheWriteTokens", number(row, 5));
            return point;
        }).toList();
    }

    private List<Trace> filtered(Instant from, Instant to, String project) {
        List<Trace> traces;
        if (from != null && to != null) {
            traces = traceRepository.findByStartedAtBetweenOrderByStartedAtAsc(from, to);
        } else if (from != null) {
            traces = traceRepository.findByStartedAtGreaterThanEqualOrderByStartedAtAsc(from);
        } else if (to != null) {
            traces = traceRepository.findByStartedAtLessThanEqualOrderByStartedAtAsc(to);
        } else {
            traces = traceRepository.findTop2000ByOrderByStartedAtDesc();
        }

        if (project != null && !project.isBlank()) {
            return traces.stream()
                    .filter(trace -> project.equalsIgnoreCase(trace.getProject()))
                    .toList();
        }

        return traces;
    }

    private Map<String, Object> totals(List<Trace> traces) {
        long input = sum(traces, Trace::getInputTokens);
        long output = sum(traces, Trace::getOutputTokens);
        long cacheRead = sum(traces, Trace::getCacheReadTokens);
        long cacheWrite = sum(traces, Trace::getCacheWriteTokens);
        long total = sum(traces, Trace::getTotalTokens);

        long sessions = traces.stream()
                .map(Trace::getSessionId)
                .filter(Objects::nonNull)
                .distinct()
                .count();
        long traceCount = traces.stream()
                .map(Trace::getTraceId)
                .filter(Objects::nonNull)
                .distinct()
                .count();
        long calls = traces.stream()
                .filter(trace -> "llm".equalsIgnoreCase(trace.getKind()))
                .count();

        double tokensPerCall = calls == 0 ? 0 : (double) total / calls;
        double tokensPerSession = sessions == 0 ? 0 : (double) total / sessions;
        double averageDuration = traces.stream()
                .filter(trace -> "llm".equalsIgnoreCase(trace.getKind()))
                .mapToLong(Trace::getDurationMs)
                .average()
                .orElse(0);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("spans", traces.size());
        summary.put("tokens", total);
        summary.put("inputTokens", input);
        summary.put("outputTokens", output);
        summary.put("cacheReadTokens", cacheRead);
        summary.put("cacheWriteTokens", cacheWrite);
        summary.put("traces", traceCount);
        summary.put("sessions", sessions);
        summary.put("llmCalls", calls);
        summary.put("tokensPerCall", Math.round(tokensPerCall));
        summary.put("tokensPerSession", Math.round(tokensPerSession));
        summary.put("avgDurationMs", Math.round(averageDuration));
        summary.put("agents", traces.stream()
                .map(Trace::getAgent)
                .filter(Objects::nonNull)
                .distinct()
                .count());
        return summary;
    }

    private String normalizeFilter(String value) {
        return value == null || value.isBlank() ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private long number(Object[] row, int index) {
        if (row == null || index >= row.length || row[index] == null) return 0L;
        return row[index] instanceof Number number ? number.longValue() : Long.parseLong(row[index].toString());
    }

    private long sum(List<Trace> traces, Function<Trace, Integer> extractor) {
        return traces.stream()
                .map(extractor)
                .filter(Objects::nonNull)
                .mapToLong(Integer::longValue)
                .sum();
    }
}
