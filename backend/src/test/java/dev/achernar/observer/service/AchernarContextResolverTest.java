package dev.achernar.observer.service;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class AchernarContextResolverTest {
    private final AchernarContextResolver resolver = new AchernarContextResolver();

    @Test void readsOnlyToolOutputForProjectYaml() {
        String input = "Called the Read tool with the following input: config/PROJECT.yaml\n"
                + "<content>\n5: project:\n8:   id: lavagem-automotiva\n11:   name: Lavagem\n"
                + "22: observability:\n</content>";
        assertEquals("lavagem-automotiva", resolver.fromMessages(input));
    }

    @Test void ignoresArbitraryPrompt() {
        assertNull(resolver.fromMessages("project:\n  id: falso"));
    }

    @Test void ignoresMissingProjectFile() {
        assertNull(resolver.fromMessages("Called the Read tool <content>project:\n id: outro</content>"));
    }
}
