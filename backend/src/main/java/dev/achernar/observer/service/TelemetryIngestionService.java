package dev.achernar.observer.service;

import dev.achernar.observer.model.Trace;
import dev.achernar.observer.repository.TraceRepository;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class TelemetryIngestionService {
    /** Janela para considerar dois spans LLM o mesmo passo (OTEL x plugin). */
    static final long DEDUP_WINDOW_SECONDS = 60L;

    private final TraceRepository repository;
    private final ProjectResolver projectResolver;
    private final LiveTelemetryService liveTelemetry;

    public TelemetryIngestionService(TraceRepository repository,
                                     ProjectResolver projectResolver,
                                     LiveTelemetryService liveTelemetry) {
        this.repository = repository;
        this.projectResolver = projectResolver;
        this.liveTelemetry = liveTelemetry;
    }

    public Trace ingest(Trace trace, String notificationType) {
        if (trace == null) return null;
        Optional<Trace> duplicate = tryMergeDuplicate(trace);
        if (duplicate.isPresent()) {
            liveTelemetry.publish(notificationType == null || notificationType.isBlank() ? "telemetry" : notificationType);
            return duplicate.get();
        }
        trace.setProject(projectResolver.resolve(trace));
        Trace saved = repository.save(trace);
        liveTelemetry.publish(notificationType == null || notificationType.isBlank() ? "telemetry" : notificationType);
        return saved;
    }

    /**
     * O mesmo passo do modelo chega duas vezes: span LiteLLM via OTEL e
     * {@code session.step.ended} via plugin OpenCode, com mesma sessão, mesmo
     * modelo e mesmo input. Os totais podem divergir porque o OTEL soma o
     * reasoning no output (333) enquanto o plugin separa output (212) +
     * reasoning (121) — por isso a igualdade de totais não é exigida: vale
     * input igual + (totais iguais OU mesmo texto de resposta).
     * Quando encontra o gêmeo já persistido, enriquece (agent/prompt que falta)
     * e retorna — o chamador publica o evento live e pula o insert duplicado.
     */
    public Optional<Trace> tryMergeDuplicate(Trace candidate) {
        if (candidate == null || !"llm".equalsIgnoreCase(candidate.getKind())) return Optional.empty();
        if (candidate.getSessionId() == null || candidate.getSessionId().isBlank()) return Optional.empty();
        if (candidate.getInputTokens() == null || candidate.getStartedAt() == null) return Optional.empty();
        List<Trace> twins;
        try {
            Instant from = candidate.getStartedAt().minusSeconds(DEDUP_WINDOW_SECONDS);
            Instant to = candidate.getStartedAt().plusSeconds(DEDUP_WINDOW_SECONDS);
            twins = repository.findBySessionIdAndKindAndStartedAtBetween(
                    candidate.getSessionId(), "llm", from, to);
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
        if (twins == null || twins.isEmpty()) return Optional.empty();
        for (Trace existing : twins) {
            if (existing == null || Objects.equals(existing.getId(), candidate.getId())) continue;
            if (!"llm".equalsIgnoreCase(existing.getKind())) continue;
            if (!Objects.equals(existing.getInputTokens(), candidate.getInputTokens())) continue;
            if (!modelCompatible(existing.getModel(), candidate.getModel())) continue;
            if (!sameCompletion(existing, candidate)) continue;
            enrich(existing, candidate);
            try {
                return Optional.of(repository.save(existing));
            } catch (RuntimeException ignored) {
                return Optional.of(existing);
            }
        }
        return Optional.empty();
    }

    /** Mesmo completion: totais idênticos OU mesmo texto de resposta. */
    private boolean sameCompletion(Trace left, Trace right) {
        if (Objects.equals(left.getTotalTokens(), right.getTotalTokens())
                && Objects.equals(left.getOutputTokens(), right.getOutputTokens())) {
            return true;
        }
        String a = responseText(left);
        String b = responseText(right);
        if (a.isEmpty() || b.isEmpty()) return false;
        if (a.equals(b)) return true;
        String probe = b.length() <= 100 ? b : b.substring(0, 100);
        String haystack = a.length() >= b.length() ? a : b;
        String needle = a.length() >= b.length() ? probe : (a.length() <= 100 ? a : a.substring(0, 100));
        return haystack.contains(needle);
    }

    /** Extrai o texto da resposta (JSON OTEL ou texto puro do plugin). */
    private String responseText(Trace trace) {
        String fromResponse = contentValues(trace.getResponseBody());
        if (!fromResponse.isEmpty()) return fromResponse;
        // Linha do plugin sem responseBody: o requestBody já é o texto agregado.
        return contentValues(trace.getRequestBody());
    }

    /** Junta os "content" de um array JSON ou normaliza texto puro. */
    private String contentValues(String body) {
        if (body == null || body.isBlank()) return "";
        String trimmed = body.trim();
        if (!trimmed.startsWith("[") && !trimmed.startsWith("{")) {
            return normalize(trimmed);
        }
        java.util.regex.Matcher matcher = CONTENT_PATTERN.matcher(trimmed);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            if (!out.isEmpty()) out.append('\n');
            out.append(normalize(matcher.group(1)));
        }
        return out.length() == 0 ? normalize(trimmed) : out.toString();
    }

    private String normalize(String value) {
        return value.replace("\\n", "\n").replace("\\r", "").replace("\\\"", "\"").trim();
    }

    private static final java.util.regex.Pattern CONTENT_PATTERN =
            java.util.regex.Pattern.compile("\"content\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    private boolean modelCompatible(String left, String right) {
        if (left == null || left.isBlank() || right == null || right.isBlank()) return true;
        return left.equalsIgnoreCase(right);
    }

    /** Preenche no gêmeo o que falta (agent do plugin, prompt cheio do OTEL). */
    private void enrich(Trace existing, Trace candidate) {
        if (isBlank(existing.getAgent()) && !isBlank(candidate.getAgent())) {
            existing.setAgent(candidate.getAgent());
        }
        if (isBlank(existing.getModel()) && !isBlank(candidate.getModel())) {
            existing.setModel(candidate.getModel());
        }
        if (isBlank(existing.getRequestBody()) && !isBlank(candidate.getRequestBody())) {
            existing.setRequestBody(candidate.getRequestBody());
        }
        if (isBlank(existing.getResponseBody()) && !isBlank(candidate.getResponseBody())) {
            existing.setResponseBody(candidate.getResponseBody());
        }
        if (isBlank(existing.getProject()) && !isBlank(candidate.getProject())) {
            existing.setProject(candidate.getProject());
        }
        existing.setDurationMs(Math.max(existing.getDurationMs(), candidate.getDurationMs()));
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
