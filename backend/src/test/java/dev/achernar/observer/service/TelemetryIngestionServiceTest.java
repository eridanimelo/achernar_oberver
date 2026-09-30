package dev.achernar.observer.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import dev.achernar.observer.model.Trace;
import dev.achernar.observer.repository.TraceRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class TelemetryIngestionServiceTest {

    private TelemetryIngestionService service(TraceRepository repository) {
        ProjectResolver resolver = mock(ProjectResolver.class);
        when(resolver.resolve(any(Trace.class))).thenAnswer(inv -> ((Trace) inv.getArgument(0)).getProject());
        return new TelemetryIngestionService(repository, resolver, mock(LiveTelemetryService.class));
    }

    private Trace llm(String id, String session, String model, int input, int output, int total, Instant at) {
        Trace trace = new Trace();
        trace.setId(id);
        trace.setKind("llm");
        trace.setSessionId(session);
        trace.setModel(model);
        trace.setInputTokens(input);
        trace.setOutputTokens(output);
        trace.setTotalTokens(total);
        trace.setStartedAt(at);
        return trace;
    }

    private void stubTwins(TraceRepository repository, String session, List<Trace> twins) {
        when(repository.findBySessionIdAndKindAndStartedAtBetween(
                eq(session), eq("llm"), any(Instant.class), any(Instant.class)))
                .thenReturn(twins);
    }

    @Test void duplicateOtelAndPluginMergesInsteadOfInserting() {
        Instant base = Instant.parse("2026-09-30T11:01:25.239828Z");
        Trace otel = llm("otel-1", "ses_1", "deepseek-v4-flash", 8298, 87, 8385, base);
        otel.setRequestBody("[full prompt]");
        otel.setResponseBody("same answer");
        // OTEL ainda sem agent; plugin traz o agent.
        Trace plugin = llm("evt_1", "ses_1", "deepseek-v4-flash", 8298, 87, 8385, base.plusSeconds(2));
        plugin.setAgent("build");
        plugin.setResponseBody("same answer");

        TraceRepository repository = mock(TraceRepository.class);
        stubTwins(repository, "ses_1", List.of(otel));
        when(repository.save(any(Trace.class))).thenAnswer(inv -> inv.getArgument(0));

        TelemetryIngestionService svc = service(repository);
        Trace result = svc.ingest(plugin, "opencode");

        assertSame(otel, result, "gêmeo OTEL x plugin deve fundir, não criar 2ª linha");
        assertEquals("build", otel.getAgent(), "merge preenche o agent que faltava no OTEL");
        assertEquals("[full prompt]", otel.getRequestBody());
        verify(repository, never()).save(argThat(t -> t != null && "evt_1".equals(t.getId())));
    }

    @Test void reasoningSplitStillMergesOnSameAnswer() {
        // OTEL soma reasoning no output (333); plugin separa output (212).
        // Mesmo input + mesmo texto = mesmo passo.
        Instant base = Instant.parse("2026-09-30T13:36:48.950482Z");
        Trace otel = llm("otel-main", "ses_1", "deepseek-v4-flash", 8298, 333, 8631, base);
        otel.setResponseBody("[{\"content\":\"Bom dia! Sou o orquestrador.\",\"role\":\"assistant\"}]");
        Trace plugin = llm("evt_1", "ses_1", "deepseek-v4-flash", 8298, 212, 8510, base.plusSeconds(4));
        plugin.setAgent("build");
        plugin.setResponseBody("Bom dia! Sou o orquestrador.");

        TraceRepository repository = mock(TraceRepository.class);
        stubTwins(repository, "ses_1", List.of(otel));
        when(repository.save(any(Trace.class))).thenAnswer(inv -> inv.getArgument(0));

        Trace result = service(repository).ingest(plugin, "opencode");

        assertSame(otel, result, "totais divergentes por reasoning devem fundir pelo texto");
        assertEquals("build", otel.getAgent());
        verify(repository, never()).save(argThat(t -> t != null && "evt_1".equals(t.getId())));
    }

    @Test void sameInputButDifferentAnswerIsKept() {
        Instant base = Instant.parse("2026-09-30T13:36:48.950482Z");
        Trace first = llm("otel-1", "ses_1", "deepseek-v4-flash", 8298, 333, 8631, base);
        first.setResponseBody("resposta A completamente diferente aqui");
        Trace second = llm("evt_2", "ses_1", "deepseek-v4-flash", 8298, 212, 8510, base.plusSeconds(4));
        second.setResponseBody("resposta B sem nada em comum com a anterior mesmo");

        TraceRepository repository = mock(TraceRepository.class);
        stubTwins(repository, "ses_1", List.of(first));
        when(repository.save(any(Trace.class))).thenAnswer(inv -> inv.getArgument(0));

        Trace result = service(repository).ingest(second, "opencode");

        assertEquals("evt_2", result.getId(), "texto diferente = passo distinto, mantém");
        verify(repository).save(second);
    }

    @Test void titleGeneratorWithDifferentTokensIsNotADuplicate() {
        Instant base = Instant.parse("2026-09-30T11:01:25.199101Z");
        Trace title = llm("otel-title", "ses_1", "deepseek-v4-flash", 547, 9, 556, base);

        TraceRepository repository = mock(TraceRepository.class);
        stubTwins(repository, "ses_1", List.of());
        when(repository.save(any(Trace.class))).thenAnswer(inv -> inv.getArgument(0));

        TelemetryIngestionService svc = service(repository);
        Trace result = svc.ingest(title, "llm");

        assertEquals("otel-title", result.getId(), "input diferente = chamada distinta, mantém");
        verify(repository).save(title);
    }

    @Test void distinctStepsWithDifferentTotalsAreKept() {
        Instant base = Instant.parse("2026-09-30T11:04:20.326Z");
        TraceRepository repository = mock(TraceRepository.class);
        stubTwins(repository, "ses_1", List.of());
        when(repository.save(any(Trace.class))).thenAnswer(inv -> inv.getArgument(0));

        TelemetryIngestionService svc = service(repository);
        Trace next = llm("otel-2", "ses_1", "deepseek-v4-flash", 47000, 708, 47708, base);
        assertEquals("otel-2", svc.ingest(next, "llm").getId());
        verify(repository).save(next);
    }

    @Test void mergeFailureFallsBackToNormalInsert() {
        Trace plugin = llm("evt_9", "ses_9", "m", 1, 2, 3, Instant.now());
        TraceRepository repository = mock(TraceRepository.class);
        when(repository.findBySessionIdAndKindAndStartedAtBetween(
                any(), any(), any(Instant.class), any(Instant.class)))
                .thenThrow(new RuntimeException("db down"));
        when(repository.save(any(Trace.class))).thenAnswer(inv -> inv.getArgument(0));

        assertEquals("evt_9", service(repository).ingest(plugin, "opencode").getId());
    }
}
