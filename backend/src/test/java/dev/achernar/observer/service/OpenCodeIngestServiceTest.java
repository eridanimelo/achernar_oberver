package dev.achernar.observer.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.achernar.observer.model.Trace;
import dev.achernar.observer.repository.TraceRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class OpenCodeIngestServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final TraceRepository repository = mock(TraceRepository.class);
    private final OpenCodeIngestService service =
            new OpenCodeIngestService(mock(TelemetryIngestionService.class), repository, objectMapper);

    private JsonNode json(String value) {
        return objectMapper.readTree(value);
    }

    @Test void stepEndedBecomesVisibleLlmSpanWithTokens() {
        Trace previous = new Trace();
        previous.setModel("deepseek-v4-flash");
        previous.setAgent("build");
        when(repository.findFirstBySessionIdAndModelIsNotNullOrderByStartedAtDesc(anyString()))
                .thenReturn(Optional.of(previous));

        Trace trace = service.normalize(json("""
                {"data":{"assistantMessageID":"msg_1","cost":0,"rawFinish":"tool_calls",
                 "finish":"tool-calls","tokens":{"output":329,"input":603,
                 "cache":{"read":54528,"write":0},"reasoning":417},
                 "sessionID":"ses_123"},
                 "created":1790642628482,"project":"estudo_ia",
                 "location":{"directory":"/Users/me/DEV/estudo_ia/.ia"},
                 "id":"evt_1","type":"session.step.ended"}
                """));

        assertEquals("llm", trace.getKind(), "evento com tokens precisa ser listado no front");
        assertEquals("ses_123", trace.getSessionId());
        assertEquals("chat deepseek-v4-flash", trace.getName());
        assertEquals("deepseek-v4-flash", trace.getModel(), "modelo emprestado do step.started");
        assertEquals("build", trace.getAgent());
        assertEquals("estudo_ia", trace.getProject());
        // Convenção Anthropic: input inclui o cache (603 frescos + 54528 cache).
        assertEquals(603 + 54528, trace.getInputTokens());
        assertEquals(329, trace.getOutputTokens());
        assertEquals(54528, trace.getCacheReadTokens());
        assertEquals(0, trace.getCacheWriteTokens());
        assertEquals(603 + 54528 + 329, trace.getTotalTokens());
    }

    @Test void cumulativeUsageIsNotCountedAsLlm() {
        Trace trace = service.normalize(json("""
                {"data":{"cost":0,"tokens":{"output":4636,"input":40924,
                 "cache":{"read":609792,"write":0},"reasoning":10683},
                 "sessionID":"ses_123"},
                 "created":1790642623440,"project":"estudo_ia",
                 "id":"evt_2","type":"session.usage.updated"}
                """));

        assertEquals("session", trace.getKind(), "acumulado da sessão não pode inflar os tokens");
        assertNull(trace.getTotalTokens());
        assertNull(trace.getInputTokens());
    }

    @Test void stepStartedCarriesModelButIsNotALlmCall() {
        Trace trace = service.normalize(json("""
                {"data":{"assistantMessageID":"msg_1","agent":"build",
                 "model":{"providerID":"litellm","variant":"default","id":"deepseek-v4-flash"},
                 "started":1790642628515,"sessionID":"ses_123"},
                 "created":1790642630182,"project":"estudo_ia",
                 "id":"evt_3","type":"session.step.started"}
                """));

        assertEquals("session", trace.getKind());
        assertEquals("deepseek-v4-flash", trace.getModel());
        assertEquals("build", trace.getAgent());
    }

    @Test void toolEventsStayToolKind() {
        Trace trace = service.normalize(json("""
                {"data":{"id":"call_1","sessionID":"ses_123"},
                 "created":1790642523531,"project":"estudo_ia",
                 "id":"evt_4","type":"session.tool.called"}
                """));

        assertEquals("tool", trace.getKind());
        assertEquals("ses_123", trace.getSessionId());
    }

    @Test void fallsBackToDirectoryBasenameWhenProjectMissing() {
        Trace trace = service.normalize(json("""
                {"data":{"sessionID":"ses_9"},"created":1790642523531,
                 "location":{"directory":"/Users/me/DEV/meu-projeto"},
                 "id":"evt_5","type":"session.created"}
                """));

        assertEquals("meu-projeto", trace.getProject());
    }

    @Test void stepEndedAggregatesSessionSiblingsIntoConversation() {
        when(repository.findFirstBySessionIdAndModelIsNotNullOrderByStartedAtDesc(anyString()))
                .thenReturn(Optional.empty());
        when(repository.findBySessionIdOrderByStartedAtAsc("ses_123")).thenReturn(java.util.List.of(
                sibling("evt_d1", "session.text.delta", "msg_1",
                        java.util.Map.of("delta", "Olá ", "sessionID", "ses_123", "assistantMessageID", "msg_1")),
                sibling("evt_d2", "session.text.delta", "msg_1",
                        java.util.Map.of("delta", "mundo", "sessionID", "ses_123", "assistantMessageID", "msg_1")),
                sibling("evt_t1", "session.tool.called", "msg_1",
                        java.util.Map.of("id", "call_1", "sessionID", "ses_123",
                                "assistantMessageID", "msg_1",
                                "input", java.util.Map.of("command", "ls"))),
                sibling("evt_t2", "session.tool.success", "msg_1",
                        java.util.Map.of("id", "call_1", "sessionID", "ses_123",
                                "assistantMessageID", "msg_1",
                                "content", java.util.List.of(java.util.Map.of("text", "ok", "type", "text"))))));

        Trace trace = service.normalize(json("""
                {"data":{"assistantMessageID":"msg_1","cost":0,
                 "tokens":{"output":10,"input":20,"cache":{"read":0,"write":0}},
                 "sessionID":"ses_123"},
                 "created":1790642628482,"project":"estudo_ia",
                 "id":"evt_1","type":"session.step.ended"}
                """));

        assertNotNull(trace.getRequestBody(), "Fluxo/Conversa precisam do corpo agregado");
        assertTrue(trace.getRequestBody().contains("Ol\u00e1 mundo"));
        assertTrue(trace.getRequestBody().contains("tool_calls"));
        assertEquals("Ol\u00e1 mundo", trace.getResponseBody());
    }

    @Test void enrichNormalizesLegacyInputTokens() {
        Trace legacy = new Trace();
        legacy.setId("evt_old");
        legacy.setKind("llm");
        legacy.setSessionId("ses_123");
        legacy.setInputTokens(1740);
        legacy.setOutputTokens(477);
        legacy.setCacheReadTokens(17408);
        legacy.setTotalTokens(2217);
        when(repository.findBySessionIdOrderByStartedAtAsc("ses_123"))
                .thenReturn(java.util.List.of());

        assertTrue(service.enrichFromSession(legacy, null));
        assertEquals(1740 + 17408, legacy.getInputTokens());
        assertEquals(1740 + 17408 + 477, legacy.getTotalTokens());
    }

    private Trace sibling(String id, String eventType, String messageId, java.util.Map<String, Object> data) {
        Trace trace = new Trace();
        trace.setId(id);
        trace.setSessionId("ses_123");
        trace.setName(eventType);
        trace.setMetadata(java.util.Map.of(
                "source", "opencode",
                "eventType", eventType,
                "raw", java.util.Map.of("data", data, "type", eventType)));
        return trace;
    }
}
