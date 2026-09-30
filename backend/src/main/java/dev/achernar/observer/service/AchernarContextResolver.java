package dev.achernar.observer.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Editor-agnostic PROJECT.yaml discovery.
 *
 * <p>Public contract is only {@code PROJECT.yaml} content found in telemetry already
 * received (span attributes like {@code gen_ai.input.messages} or OTEL events).
 * No dependency on editor, directory layout ({@code .ia/}, {@code config/},
 * {@code AGENTS.md}, ...) or file-system access: parsing happens in memory during
 * ingestion and only the extracted metadata is persisted.
 */
@Service
public class AchernarContextResolver {

    private static final Logger log = LoggerFactory.getLogger(AchernarContextResolver.class);

    /** Bound to keep parsing cheap; system context (where PROJECT.yaml lives) comes first. */
    private static final int MAX_SCAN_CHARS = 64_000;

    private final ObjectMapper objectMapper;

    public AchernarContextResolver(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** Extracted {@code project:} block. Only {@code name} is required. */
    public record ProjectInfo(String name, String description, String version, String organization) {}

    /** Backwards-compatible entry: project name from span messages. */
    public String fromMessages(Object raw) {
        ProjectInfo info = resolve(raw, null);
        return info == null ? null : info.name();
    }

    /**
     * Central entry for span/event content. Accepts raw attribute values
     * (String, JsonNode, List, Map) plus the OTEL {@code events} node.
     */
    public ProjectInfo resolve(Object messagesRaw, JsonNode eventsNode) {
        try {
            StringBuilder searchable = new StringBuilder();
            String messages = normalize(messagesRaw);
            if (messages != null && !messages.isBlank()) {
                searchable.append(messages);
            }
            String events = normalize(eventsNode);
            if (events != null && !events.isBlank()) {
                if (!searchable.isEmpty()) searchable.append('\n');
                searchable.append(events);
            }
            if (searchable.isEmpty()) return null;
            return parseProjectYaml(searchable.toString());
        } catch (Exception error) {
            log.debug("PROJECT.yaml discovery failed, continuing with fallbacks: {}", error.toString());
            return null;
        }
    }

    private String normalize(Object raw) {
        if (raw == null) return null;
        try {
            if (raw instanceof String text) return cap(text);
            if (raw instanceof JsonNode node) {
                if (node.isMissingNode() || node.isNull()) return null;
                if (node.isString()) return cap(node.asString());
                return cap(node.toString());
            }
            // List/Map (OTLP arrayValue/kvlistValue conversions) -> JSON, keeps \n escapes.
            return cap(objectMapper.valueToTree(raw).toString());
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String cap(String text) {
        if (text == null) return null;
        return text.length() > MAX_SCAN_CHARS ? text.substring(0, MAX_SCAN_CHARS) : text;
    }

    /**
     * Conservative parser: requires a standalone {@code project:} line followed by an
     * indented {@code name:} child. Plain sentences mentioning "project" never match.
     */
    static ProjectInfo parseProjectYaml(String input) {
        if (input == null || input.isBlank()) return null;
        // Payloads arrive serialized (OTEL attribute JSON inside OTLP JSON), so
        // escapes can be nested: {"content":"project:\\n  name: X"}. Unescape
        // until stable (bounded) before scanning line structure.
        String content = unescape(input);
        if (content.length() > MAX_SCAN_CHARS) content = content.substring(0, MAX_SCAN_CHARS);
        String[] lines = content.split("\n", -1);

        for (int i = 0; i < lines.length; i++) {
            String header = stripLineNumber(lines[i]);
            if (!isProjectHeader(header)) continue;
            int baseIndent = indentOf(header);
            String name = null, description = null, version = null, organization = null;
            for (int j = i + 1; j < lines.length; j++) {
                String raw = stripLineNumber(lines[j]);
                if (raw.isBlank() || raw.strip().startsWith("#")) continue;
                int indent = indentOf(raw);
                if (indent <= baseIndent) break; // block ended
                String clean = raw.strip();
                String value;
                if ((value = fieldValue(clean, "name")) != null && name == null) {
                    name = cleanName(value);
                } else if ((value = fieldValue(clean, "description")) != null && description == null) {
                    description = cleanScalar(value, 500);
                } else if ((value = fieldValue(clean, "version")) != null && version == null) {
                    version = cleanScalar(value, 50);
                } else if ((value = fieldValue(clean, "organization")) != null && organization == null) {
                    organization = cleanScalar(value, 200);
                }
                if (name != null && description != null && version != null && organization != null) break;
            }
            if (name != null) return new ProjectInfo(name, description, version, organization);
            // else: 'project:' without valid 'name:' -> keep scanning, never guess.
        }
        return null;
    }

    /** Repeated JSON-unescape for nested serialization (attribute JSON in OTLP JSON). */
    private static String unescape(String value) {
        String current = value;
        for (int pass = 0; pass < 3; pass++) {
            String next = current
                    .replace("\\\\", "\uE000")
                    .replace("\\n", "\n")
                    .replace("\\r", "\r")
                    .replace("\\t", "\t")
                    .replace("\\\"", "\"")
                    .replace("\\'", "'")
                    .replace("\uE000", "\\");
            if (next.equals(current)) return current;
            current = next;
        }
        return current;
    }

    /** Strips Read-tool line prefixes like {@code "8:   name: x"}. */
    private static String stripLineNumber(String line) {
        if (line == null) return "";
        return line.replaceFirst("^\\s*\\d+\\s*:\\s?", "");
    }

    /** A header is {@code project:} on its own line, optionally embedded in JSON
     *  framing ({@code ..."content":"project:}) after unescaping. Inline values like
     *  {@code {"project": "foo"}} or prose ("about the project: ...") never match;
     *  the indented {@code name:} child requirement is the second guard. */
    private static boolean isProjectHeader(String line) {
        String stripped = line.strip();
        if (stripped.matches("^project:\\s*(#.*)?$")) return true;
        if (stripped.matches("^.*[\"'{\\[:,]project:\\s*(#.*)?$")) return true;
        return false;
    }

    private static int indentOf(String line) {
        int count = 0;
        for (int k = 0; k < line.length(); k++) {
            char c = line.charAt(k);
            if (c == ' ' || c == '\t') count++;
            else break;
        }
        return count;
    }

    private static String fieldValue(String cleanLine, String field) {
        String lower = cleanLine.toLowerCase(java.util.Locale.ROOT);
        String prefix = field.toLowerCase(java.util.Locale.ROOT) + ":";
        if (!lower.startsWith(prefix)) return null;
        String rest = cleanLine.substring(prefix.length()).trim();
        if (rest.startsWith("#")) return null;
        // Inline comment after value: 'name: Foo # comment' -> 'Foo'.
        int hash = rest.indexOf(" #");
        if (hash >= 0) rest = rest.substring(0, hash).trim();
        return rest.isEmpty() ? null : rest;
    }

    private static String cleanName(String value) {
        String clean = cleanScalar(value, 200);
        if (clean == null || clean.length() < 2) return null;
        // Reject placeholders and structural fragments, never real names.
        String lower = clean.toLowerCase(java.util.Locale.ROOT);
        if (lower.equals("...") || lower.equals("xxx") || lower.equals("todo") || lower.equals("fixme")
                || (clean.startsWith("<") && clean.endsWith(">"))
                || lower.equals("your_project_name") || lower.equals("your project name")) {
            return null;
        }
        if (clean.contains("{{") || clean.contains("}}") || clean.contains("://")) return null;
        if (!clean.matches(".*[\\p{L}\\p{N}].*")) return null;
        // Must look like a name, not a sentence ("Create a project service..." has spaces+verb;
        // real names are short). Sentences with 8+ words are rejected.
        if (clean.split("\\s+").length > 8) return null;
        if (clean.contains(". ") || clean.endsWith(".")) return null;
        return clean;
    }

    private static String cleanScalar(String value, int max) {
        if (value == null) return null;
        String clean = value.trim();
        // Trailing framing of the enclosing JSON string ("...version: 1.0"}).
        clean = clean.replaceAll("[\"'\\]},\\]]+$", "").trim();
        if (clean.length() >= 2
                && ((clean.startsWith("\"") && clean.endsWith("\""))
                        || (clean.startsWith("'") && clean.endsWith("'")))) {
            clean = clean.substring(1, clean.length() - 1).trim();
        }
        if (clean.isBlank() || clean.length() > max) return null;
        if (clean.startsWith("{") || clean.startsWith("[")) return null;
        return clean;
    }
}
