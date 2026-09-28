package dev.achernar.observer.service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/** Opt-in fallback for a verified PROJECT.yaml tool-read segment. */
@Service
public class AchernarContextResolver {
    private static final Pattern ID = Pattern.compile("^id:\\s*['\"]?([A-Za-z0-9][A-Za-z0-9._-]{0,199})['\"]?\\s*$");

    public String fromMessages(Object raw) {
        if (!(raw instanceof String messages)) return null;
        // In OpenCode the read-tool content is JSON-escaped inside the messages string.
        String content = messages.replace("\\n", "\n").replace("\\\"", "\"");
        int file = content.indexOf("config/PROJECT.yaml");
        if (file < 0 || !content.contains("Called the Read tool")) return null;
        int start = content.indexOf("<content>", file);
        int end = start < 0 ? -1 : content.indexOf("</content>", start);
        if (start < 0 || end < 0 || end - start > 32_000) return null;
        boolean project = false;
        for (String line : content.substring(start + 9, end).split("\\n")) {
            String clean = line.replaceFirst("^\\s*\\d+:\\s?", "");
            if (clean.strip().equals("project:")) { project = true; continue; }
            if (project && !clean.isBlank() && !Character.isWhitespace(clean.charAt(0))) break;
            if (project) {
                Matcher match = ID.matcher(clean.strip());
                if (match.matches()) return match.group(1);
            }
        }
        return null;
    }
}
