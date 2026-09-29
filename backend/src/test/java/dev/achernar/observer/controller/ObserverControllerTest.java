package dev.achernar.observer.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.achernar.observer.model.Trace;
import dev.achernar.observer.service.OpenCodeIngestService;
import dev.achernar.observer.service.TelemetryIngestionService;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class ObserverControllerTest {

    @Test void openCodeEventReturnsNormalizedLlmTrace() {
        ObjectMapper objectMapper = new ObjectMapper();
        TelemetryIngestionService ingestion = mock(TelemetryIngestionService.class);
        // Passa direto: normaliza de verdade, só pula o save no banco.
        when(ingestion.ingest(any(Trace.class), eq("opencode")))
                .thenAnswer(invocation -> invocation.getArgument(0));
        OpenCodeIngestService openCodeIngestService =
                new OpenCodeIngestService(ingestion, mock(), objectMapper);
        ObserverController controller =
                new ObserverController(null, null, null, null, openCodeIngestService);

        JsonNode payload = objectMapper.readTree("""
                {"data":{"assistantMessageID":"msg_1","cost":0,
                 "tokens":{"output":329,"input":603,
                 "cache":{"read":54528,"write":0}},
                 "sessionID":"ses_123"},
                 "created":1790642628482,"project":"estudo_ia",
                 "id":"evt_1","type":"session.step.ended"}
                """);

        ResponseEntity<?> response = controller.openCodeEvent(payload);

        assertEquals(200, response.getStatusCode().value());
        assertInstanceOf(Trace.class, response.getBody());
        Trace trace = (Trace) response.getBody();
        assertEquals("llm", trace.getKind());
        assertEquals(603 + 54528, trace.getInputTokens());
        assertEquals(603 + 54528 + 329, trace.getTotalTokens());
    }
}
