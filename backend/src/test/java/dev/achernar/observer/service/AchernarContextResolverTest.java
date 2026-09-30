package dev.achernar.observer.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.achernar.observer.model.Trace;
import dev.achernar.observer.repository.TraceRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class AchernarContextResolverTest {

    private final AchernarContextResolver resolver = new AchernarContextResolver(new ObjectMapper());

    @Test void completeProjectYaml() {
        var info = AchernarContextResolver.parseProjectYaml("""
                project:
                  name: Sistema de Gestão de Lavagem Automotiva
                  description: Gestão de clientes, veículos, serviços de lavagem e agendamentos
                  version: 1.0.0
                  organization: TS1506CS
                """);
        assertNotNull(info);
        assertEquals("Sistema de Gestão de Lavagem Automotiva", info.name());
        assertEquals("Gestão de clientes, veículos, serviços de lavagem e agendamentos", info.description());
        assertEquals("1.0.0", info.version());
        assertEquals("TS1506CS", info.organization());
    }

    @Test void onlyRequiredField() {
        var info = AchernarContextResolver.parseProjectYaml("project:\n  name: Meu Projeto\n");
        assertNotNull(info);
        assertEquals("Meu Projeto", info.name());
        assertNull(info.description());
    }

    @Test void insideMarkdownFence() {
        String input = "Project configuration:\n\n```yaml\nproject:\n  name: Meu Projeto\n  description: Projeto de teste\n```\n";
        assertEquals("Meu Projeto", resolver.fromMessages(input));
    }

    @Test void withSurroundingInstructions() {
        String input = "You are a coding assistant. Follow the repo rules.\n"
                + "PROJECT.yaml:\nproject:\n  name: Meu Projeto\n  version: 2.0\n"
                + "Always answer in Portuguese. Be concise.\n";
        assertEquals("Meu Projeto", resolver.fromMessages(input));
    }

    @Test void normalTextDoesNotIdentify() {
        assertNull(resolver.fromMessages("Create a project service for managing customers."));
        assertNull(resolver.fromMessages("The project is responsible for managing vehicles."));
    }

    @Test void invalidYamlFallsThroughWithoutThrowing() {
        assertNull(resolver.fromMessages("project:\n"));
        assertNull(resolver.fromMessages("project:\n  description: no name here\n"));
        assertNull(resolver.fromMessages("project: Meu Projeto"));
        assertNull(resolver.fromMessages(null));
        assertNull(resolver.fromMessages(""));
    }

    @Test void quotedAndJsonEscapedContent() {
        String input = "{\\\"role\\\":\\\"system\\\",\\\"content\\\":\\\"project:\\\\n  name: Lavagem Top\\\\n  version: 1.0\\\"}";
        assertEquals("Lavagem Top", resolver.fromMessages(input));
    }

    @Test void readToolNumberedOutput() {
        String input = "Called the Read tool with the following input: PROJECT.yaml\n"
                + "<content>\n5: project:\n8:   name: lavagem-automotiva\n11:   version: 1.0\n</content>";
        assertEquals("lavagem-automotiva", resolver.fromMessages(input));
    }

    @Test void ignoresLegacyIdWithoutName() {
        // Old contract used `id:` under a `config/PROJECT.yaml` marker; new contract
        // requires `project.name` and knows no directory layout.
        assertNull(resolver.fromMessages(
                "Called the Read tool with the following input: config/PROJECT.yaml\n"
                        + "<content>\n5: project:\n8:   id: lavagem-automotiva\n</content>"));
    }

    @Test void correlationReusesProjectWithoutYaml() {
        // Case 8: second call of the same trace/session inherits Projeto A via ProjectResolver.
        TraceRepository repository = mock(TraceRepository.class);
        Trace known = new Trace();
        known.setProject("Projeto A");
        when(repository.findFirstByTraceIdAndProjectIsNotNullAndProjectNotIgnoreCaseOrderByStartedAtAsc(
                        anyString(), anyString()))
                .thenReturn(Optional.of(known));
        ProjectResolver projectResolver = new ProjectResolver(repository, "UNKNOWN");

        Trace secondCall = new Trace();
        secondCall.setTraceId("trace-1");
        secondCall.setProject(null); // no PROJECT.yaml in this call
        assertEquals("Projeto A", projectResolver.resolve(secondCall));
    }

    @Test void unknownPlaceholderNeverPoisonsCorrelation() {
        // A context-less http span persisted as UNKNOWN must not block inheritance,
        // and even if returned it must be rejected by validation.
        TraceRepository repository = mock(TraceRepository.class);
        Trace placeholder = new Trace();
        placeholder.setProject("UNKNOWN");
        when(repository.findFirstByTraceIdAndProjectIsNotNullAndProjectNotIgnoreCaseOrderByStartedAtAsc(
                        anyString(), anyString()))
                .thenReturn(Optional.of(placeholder));
        when(repository.findFirstBySessionIdAndProjectIsNotNullAndProjectNotIgnoreCaseOrderByStartedAtAsc(
                        anyString(), anyString()))
                .thenReturn(Optional.empty());
        ProjectResolver projectResolver = new ProjectResolver(repository, "UNKNOWN");

        Trace span = new Trace();
        span.setTraceId("trace-1");
        span.setSessionId("ses-1");
        assertEquals("UNKNOWN", projectResolver.resolve(span));
    }

    @Test void noYamlPreservesFallback() {
        assertNull(resolver.fromMessages("{\"role\":\"user\",\"content\":\"bom dia\"}"));
        ProjectResolver projectResolver = new ProjectResolver(mock(TraceRepository.class), "UNKNOWN");
        Trace span = new Trace();
        assertEquals("UNKNOWN", projectResolver.resolve(span));
    }
}
