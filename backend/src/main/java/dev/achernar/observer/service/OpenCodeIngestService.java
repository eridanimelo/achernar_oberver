package dev.achernar.observer.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import dev.achernar.observer.model.Trace;
import dev.achernar.observer.repository.TraceRepository;
import dev.achernar.observer.telemetry.TelemetryAdapter;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class OpenCodeIngestService implements TelemetryAdapter<JsonNode> {
    private final TelemetryIngestionService ingestionService;
    private final TraceRepository traceRepository;
    private final ObjectMapper objectMapper;

    public OpenCodeIngestService(TelemetryIngestionService ingestionService,
                                 TraceRepository traceRepository,
                                 ObjectMapper objectMapper) {
        this.ingestionService = ingestionService;
        this.traceRepository = traceRepository;
        this.objectMapper = objectMapper;
    }

    public Trace ingest(JsonNode payload) {
        Trace normalized = normalize(payload);
        return ingestionService.ingest(normalized, "opencode");
    }

    @Override
    public Trace normalize(JsonNode payload) {
        String eventType = text(payload, "type");
        // O OpenCode V2 entrega o corpo do evento em `data`; formatos antigos
        // (ou outros produtores) usam `properties` (+ `properties.info`) ou um
        // `info` no topo. `data` é o envelope real hoje, então tem prioridade.
        JsonNode data = node(payload, "data");
        JsonNode properties = node(payload, "properties");
        JsonNode info = node(properties, "info");
        if (info == null) info = node(payload, "info");

        String sessionId = firstText(sources(data, info, properties, payload),
                "sessionID", "sessionId", "session_id");
        // `model` pode vir como texto ou como objeto { providerID, id, variant }.
        String model = firstNonBlank(
                firstText(node(data, "model"), "id", "modelID", "modelId", "model"),
                firstText(node(info, "model"), "modelID", "modelId", "model"),
                firstText(sources(data, info, properties, payload), "modelID", "modelId", "model"));
        String provider = firstNonBlank(
                firstText(node(data, "model"), "providerID", "providerId", "provider"),
                firstText(node(info, "model"), "providerID", "providerId", "provider"),
                firstText(sources(data, info, properties, payload), "providerID", "providerId", "provider"));
        String agent = firstText(sources(data, info, properties, payload), "agent", "agentName");

        String kind = classify(eventType, data, info, properties);
        String toolName = firstText(sources(data, info, properties, payload),
                "tool", "toolName", "name", "function");
        if (toolName == null) toolName = firstText(sources(node(data, "input"), node(data, "args")), "tool", "toolName", "name");
        if ("tool".equals(kind) || "mcp".equals(kind)) {
            kind = refineToolKind(kind, toolName, data, info, properties, payload);
        }
        provider = normalizeProvider(provider);

        // `session.step.ended` traz os tokens do passo, mas não o modelo; o
        // `session.step.started`/`session.created` da mesma sessão já foi salvo
        // com o modelo, então emprestamos dali para a linha aparecer completa.
        // O mesmo `previous` serve para estimar a duração do passo quando o
        // evento só tem 1 timestamp (ended.created - started.startedAt).
        Trace previous = null;
        if (sessionId != null && (model == null || agent == null)) {
            previous = traceRepository
                    .findFirstBySessionIdAndModelIsNotNullOrderByStartedAtDesc(sessionId)
                    .orElse(null);
            if (previous != null) {
                if (model == null) model = previous.getModel();
                if (agent == null) agent = previous.getAgent();
            }
        }

        Trace trace = new Trace();
        // Cada POST do hook é um evento novo: id sempre único. O hash
        // determinístico anterior fazia upsert na mesma linha (mesmo
        // session/message/part) e o front parecia "não gravar mais nada".
        // Se o payload trouxer um id globalmente único, reaproveita;
        // senão gera random (igual ao /api/events e ao OTEL com spanId).
        trace.setId(uniqueId(payload, properties, info));
        trace.setTraceId(firstNonBlank(firstText(sources(data, info, properties, payload),
                "traceID", "traceId"), sessionId));
        trace.setSessionId(sessionId);
        trace.setParentSpanId(firstText(sources(data, info, properties, payload),
                "parentID", "parentId", "parentSpanId"));
        trace.setName(firstNonBlank(
                firstText(sources(data, info, properties, payload), "tool", "toolName"),
                "llm".equals(kind) && model != null ? "chat " + model : null,
                eventType, kind));
        trace.setKind(kind);
        trace.setAgent(agent);
        trace.setModel(model);
        trace.setStartedAt(timestamp(data, info, properties, payload));
        long durationMs = duration(data, info, properties, payload);
        if (durationMs == 0 && previous != null && previous.getStartedAt() != null
                && trace.getStartedAt() != null
                && !trace.getStartedAt().isBefore(previous.getStartedAt())) {
            durationMs = Math.max(1L,
                    trace.getStartedAt().toEpochMilli() - previous.getStartedAt().toEpochMilli());
        }
        trace.setDurationMs(durationMs);
        // Só spans LLM entram na contabilidade de tokens. Eventos de sessão e o
        // acumulado `session.usage.updated` ficam de fora para não inflar o
        // resumo (o front soma tokens de todas as linhas).
        // Convenção Anthropic (a que o front assume): `input` inclui o cache;
        // o hook manda `input` fresco + `cache.read` separado, então dobra o
        // cache para dentro do input — senão "entrada real" (input-cache) dá 0
        // e a taxa de cache estoura (cache > input).
        if ("llm".equals(kind)) {
            trace.setInputTokens(token(sources(data, info, properties),
                    "input", "inputTokens", "prompt", "promptTokens"));
            trace.setOutputTokens(token(sources(data, info, properties),
                    "output", "outputTokens", "completion", "completionTokens"));
            trace.setCacheReadTokens(nestedToken(sources(data, info, properties),
                    "cache", "read", "cacheRead", "cacheReadTokens"));
            trace.setCacheWriteTokens(nestedToken(sources(data, info, properties),
                    "cache", "write", "cacheWrite", "cacheWriteTokens"));
            if (trace.getCacheReadTokens() != null && trace.getCacheReadTokens() > 0) {
                trace.setInputTokens((trace.getInputTokens() == null ? 0 : trace.getInputTokens())
                        + trace.getCacheReadTokens());
            }
            trace.setTotalTokens(total(trace.getInputTokens(), trace.getOutputTokens()));
        }
        trace.setStatus(eventType != null && eventType.contains("error") ? 500 : 200);
        trace.setProject(resolveProject(payload, properties, data, info));
        // O front deriva Fluxo/Conversa de requestBody (mensagens OpenAI) e o
        // nó de resposta de responseBody. Sem eles o detalhe abre com fluxo 0
        // mesmo com tokens/duração preenchidos.
        trace.setRequestBody(conversationRequest(data, info, properties, payload, kind));
        trace.setResponseBody(conversationResponse(data, info, properties, payload, kind));
        if ("llm".equals(kind) && trace.getRequestBody() == null && sessionId != null) {
            // `session.step.ended` só traz tokens (+ assistantMessageID): o
            // texto/resposta está nos irmãos da sessão (text.delta,
            // reasoning.delta, tool.*). Agrega para Fluxo/Conversa voltarem.
            enrichFromSession(trace, assistantMessageId(data, info, properties, payload));
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", "opencode");
        metadata.put("provider", provider);
        // Origem declarada: tool semântica (subagent/skill/rule/agent/mcp/tool).
        // O front usa toolKind em vez de re-adivinhar por path — resolve o
        // "tudo vem como tool" mesmo para eventos antigos sem o campo.
        metadata.put("toolName", toolName);
        metadata.put("toolKind", kind);
        metadata.put("origin", originOf(provider, payload, properties, data, info));
        metadata.put("eventType", eventType);
        metadata.put("raw", objectMapper.convertValue(payload, Object.class));
        trace.setMetadata(metadata);
        return trace;
    }

    private String classify(String type, JsonNode data, JsonNode info, JsonNode properties) {
        String t = type == null ? "" : type.toLowerCase();
        if (t.startsWith("tool.") || t.contains("tool.execute")) return "tool";
        // session.tool.* são execuções de ferramenta, não chamadas de modelo.
        if (t.startsWith("session.tool.")) return "tool";
        // session.usage.updated é o acumulado da sessão, não uma chamada;
        // tratá-lo como LLM duplicaria os tokens de todos os passos.
        if (t.equals("session.usage.updated")) return "session";
        // Qualquer evento com contabilidade real de tokens é LLM: o front
        // padrão (technical=false) só lista kind='llm'. Restringir a
        // "message.updated" escondia todo o resto do front.
        if (hasUsage(data) || hasUsage(info) || hasUsage(properties)) return "llm";
        if (t.startsWith("session.")) return "session";
        if (t.contains("mcp")) return "mcp";
        if (t.contains("agent")) return "agent";
        return "opencode";
    }

    /** Contabilidade de tokens/custo real (não apenas a presença de `model`). */
    private boolean hasUsage(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return false;
        return node.has("tokens") || node.has("usage") || node.has("cost");
    }

    /**
     * Ferramentas semânticas chegam com kind genérico "tool" (task, skill,
     * leitura de SKILL.md / agents.md / rules). Reclassifica pelo nome da
     * ferramenta + path dos argumentos para o front exibir subagent / skill /
     * rule / agent em vez de "TOOL" para tudo.
     */
    private String refineToolKind(String initial, String toolName,
                                  JsonNode data, JsonNode info, JsonNode properties, JsonNode payload) {
        String name = toolName == null ? "" : toolName.toLowerCase();
        // Nome da ferramenta manda primeiro (task = subagente no OpenCode,
        // skill explícita, etc.). Vale para Copilot/Claude com outros nomes.
        if (name.contains("subagent") || name.equals("task") || name.startsWith("task_")
                || name.contains("delegate") || name.contains("dispatch")
                || name.contains("spawn") || name.startsWith("agent")) {
            return "subagent";
        }
        if (name.contains("skill")) return "skill";
        if (name.contains("rule")) return "rule";
        // Senão, o path lido nos argumentos decide (SKILL.md, agents/, rules/).
        String path = firstText(sources(data, info, properties, payload),
                "filePath", "path", "file", "target", "url");
        if (path == null) {
            JsonNode args = firstNode(sources(data, info, properties, payload),
                    "args", "arguments", "input", "params", "parameters");
            if (args != null && args.isObject()) {
                path = firstText(new JsonNode[]{args}, "filePath", "path", "file", "target", "skill", "agent", "rule");
                if (path == null) {
                    // skill invocada por nome: {"skill": "minha-skill"} / {"agent": "x"}
                    String skillArg = firstText(new JsonNode[]{args}, "skill", "skillName");
                    if (skillArg != null) return "skill";
                    String agentArg = firstText(new JsonNode[]{args}, "subagent", "subagentType", "agent", "agentName");
                    if (agentArg != null) return "subagent";
                }
            }
            if (path == null && args != null && args.isTextual()) path = args.asString();
        }
        if (path != null) {
            String lower = path.toLowerCase();
            if (lower.contains("subagent")) return "subagent";
            if (lower.contains("/skills/") || lower.contains("skill")) return "skill";
            if (lower.contains("/agents/") || lower.endsWith("agents.md")
                    || lower.contains("agent_") || lower.contains("agent-")) return "agent";
            if (lower.contains("/rules/") || lower.contains("rule")
                    || lower.endsWith(".mdc") || lower.endsWith("rules.md")) return "rule";
        }
        return initial;
    }

    /**
     * Normaliza o provider para rótulos conhecidos (copilot, claude, openai…).
     * O hook pode mandar "github-copilot", "anthropic", "litellm" etc.
     */
    private String normalizeProvider(String raw) {
        if (raw == null || raw.isBlank()) return raw;
        String lower = raw.trim().toLowerCase();
        if (lower.contains("copilot") || lower.contains("github")) return "copilot";
        if (lower.contains("claude") || lower.contains("anthropic")) return "claude";
        if (lower.contains("openai") || lower.equals("gpt")) return "openai";
        if (lower.contains("google") || lower.contains("gemini")) return "gemini";
        if (lower.contains("litellm")) return "litellm";
        return raw.trim();
    }

    /** Origem do evento para o front agrupar (opencode/copilot/claude/otel). */
    private String originOf(String provider, JsonNode... nodes) {
        String norm = provider == null ? "" : provider.toLowerCase();
        if (norm.contains("copilot")) return "copilot";
        if (norm.contains("claude")) return "claude";
        for (JsonNode node : nodes) {
            if (node == null || node.isMissingNode() || node.isNull()) continue;
            String hint = firstText(new JsonNode[]{node}, "origin", "source", "author", "agentClient");
            if (hint != null) {
                String h = hint.toLowerCase();
                if (h.contains("copilot")) return "copilot";
                if (h.contains("claude")) return "claude";
                if (h.contains("opencode")) return "opencode";
            }
        }
        return "opencode";
    }

    private Integer token(JsonNode[] nodes, String... keys) {
        for (JsonNode node : nodes) {
            if (node == null || node.isMissingNode() || node.isNull()) continue;
            JsonNode tokens = node.path("tokens");
            JsonNode usage = node.path("usage");
            Integer value = integer(tokens, keys);
            if (value == null) value = integer(usage, keys);
            if (value == null) value = integer(node, keys);
            if (value != null) return value;
        }
        return null;
    }

    private Integer nestedToken(JsonNode[] nodes, String nested, String nestedKey, String... flatKeys) {
        for (JsonNode node : nodes) {
            if (node == null || node.isMissingNode() || node.isNull()) continue;
            JsonNode nestedValue = node.path("tokens").path(nested).path(nestedKey);
            if (nestedValue.isNumber()) return nestedValue.asInt();
            nestedValue = node.path("usage").path(nested).path(nestedKey);
            if (nestedValue.isNumber()) return nestedValue.asInt();
            Integer value = integer(node, flatKeys);
            if (value != null) return value;
        }
        return null;
    }

    private Integer integer(JsonNode node, String... keys) {
        if (node == null || node.isMissingNode()) return null;
        for (String key : keys) {
            JsonNode value = node.path(key);
            if (value.isInt() || value.isLong() || value.isNumber()) return value.asInt();
            if (value.isString()) try { return Integer.valueOf(value.asString()); } catch (NumberFormatException ignored) {}
        }
        return null;
    }

    private Instant timestamp(JsonNode... nodes) {
        String[] keys = {"time", "createdAt", "created", "updatedAt", "completed", "timestamp", "start", "end"};
        for (JsonNode node : nodes) {
            if (node == null || node.isMissingNode() || node.isNull()) continue;
            Instant found = instantFrom(node, keys);
            if (found != null) return found;
            JsonNode time = node.path("time");
            if (time != null && time.isObject()) {
                found = instantFrom(time, "start", "created", "startTime", "begin", "end", "completed", "endTime", "finish", "time", "createdAt", "updatedAt", "timestamp");
                if (found != null) return found;
            }
        }
        return Instant.now();
    }

    private Instant instantFrom(JsonNode node, String... keys) {
        for (String key : keys) {
            JsonNode value = node.path(key);
            if (value == null || value.isMissingNode() || value.isNull()) continue;
            if (value.isNumber()) {
                long number = value.asLong();
                return number > 10_000_000_000L ? Instant.ofEpochMilli(number) : Instant.ofEpochSecond(number);
            }
            if (value.isString()) {
                String text = value.asString().trim();
                if (text.isEmpty()) continue;
                try { return Instant.parse(text); } catch (Exception ignored) {}
                try { return Instant.ofEpochMilli(Long.parseLong(text)); } catch (NumberFormatException ignored) {}
            }
        }
        return null;
    }

    private long duration(JsonNode... nodes) {
        for (JsonNode node : nodes) {
            if (node == null || node.isMissingNode()) continue;
            JsonNode time = node.path("time");
            Instant start = instantFrom(time, "start", "created", "startTime", "begin");
            Instant end = instantFrom(time, "end", "completed", "endTime", "finish");
            if (start == null) start = instantFrom(node, "start", "started", "created", "createdAt", "startTime", "timestamp");
            if (end == null) end = instantFrom(node, "end", "completed", "updatedAt", "endTime", "timestamp");
            // Par started/ended: costuma vir como epoch ms em nós diferentes
            // (ex.: session.step.started + session.step.ended); o startedAt já
            // carrega o `created` do evento, então a duração aqui usa o par
            // real start/end em vez de um timestamp isolado.
            if (start != null && end != null && !end.isBefore(start)) {
                return Math.max(1L, end.toEpochMilli() - start.toEpochMilli());
            }
            // Fallback numérico direto (epoch ms/s ou nanos OTEL).
            long startNum = firstLong(time, "start", "created", "startTime", "begin");
            long endNum = firstLong(time, "end", "completed", "endTime", "finish");
            if (startNum <= 0) startNum = firstLong(node, "start", "started", "created", "createdAt", "startTime");
            if (endNum <= 0) endNum = firstLong(node, "end", "completed", "updatedAt", "endTime");
            if (startNum > 0 && endNum >= startNum) {
                long diff = endNum - startNum;
                // OTEL nanos vs millis: normaliza para ms.
                if (diff > 1_000_000_000L) return diff / 1_000_000L;
                return diff;
            }
        }
        return 0;
    }

    private long firstLong(JsonNode node, String... keys) {
        for (String key : keys) {
            JsonNode value = node.path(key);
            if (value.isNumber()) return value.asLong();
            if (value.isString()) {
                try { return Long.parseLong(value.asString().trim()); } catch (NumberFormatException ignored) {}
            }
        }
        return 0;
    }

    private String conversationRequest(JsonNode data, JsonNode info, JsonNode properties, JsonNode payload, String kind) {
        JsonNode[] src = sources(data, info, properties, payload);
        JsonNode messages = firstNode(src, "messages", "inputMessages", "input", "prompt", "prompts");
        if (messages != null) {
            String normalized = toOpenAiMessages(messages);
            if (normalized != null) return normalized;
        }
        // Evento de ferramenta sem array de mensagens: sintetiza 1 tool_call
        // para o front montar o nó de fluxo (senão flowNodes fica vazio).
        if ("tool".equals(kind) || "mcp".equals(kind)) {
            String tool = firstText(src, "tool", "toolName", "name", "function", "command");
            JsonNode args = firstNode(src, "args", "arguments", "input", "params", "parameters");
            String callId = firstText(src, "callId", "callID", "toolCallId", "id");
            if (tool != null || args != null) {
                var arr = objectMapper.createArrayNode();
                var msg = objectMapper.createObjectNode();
                msg.put("role", "assistant");
                msg.put("content", "");
                var calls = objectMapper.createArrayNode();
                var call = objectMapper.createObjectNode();
                if (callId != null) call.put("id", callId);
                var fn = objectMapper.createObjectNode();
                fn.put("name", tool != null ? tool : "tool");
                if (args != null) fn.set("arguments", args);
                call.set("function", fn);
                calls.add(call);
                msg.set("tool_calls", calls);
                arr.add(msg);
                return arr.toString();
            }
        }
        // LLM sem mensagens (ex.: session.step.ended só com tokens): guarda o
        // envelope útil como 1 mensagem de sistema para o Fluxo não zerar e a
        // aba Conversa mostrar algo em vez de vazio.
        JsonNode fallback = firstNode(src, "message", "content", "text", "output", "result");
        if (fallback != null && "llm".equals(kind)) {
            String content = fallback.isString() ? fallback.asString() : fallback.toString();
            if (!content.isBlank()) {
                var arr = objectMapper.createArrayNode();
                var msg = objectMapper.createObjectNode();
                msg.put("role", "assistant");
                msg.put("content", content.length() > 4000 ? content.substring(0, 4000) : content);
                arr.add(msg);
                return arr.toString();
            }
        }
        return null;
    }

    private String conversationResponse(JsonNode data, JsonNode info, JsonNode properties, JsonNode payload, String kind) {
        JsonNode[] src = sources(data, info, properties, payload);
        JsonNode out = firstNode(src, "response", "completion", "completions", "output", "outputs", "result", "text");
        if (out == null && ("tool".equals(kind) || "mcp".equals(kind))) {
            out = firstNode(src, "content", "message");
        }
        if (out == null) return null;
        if (out.isString()) return out.asString();
        return out.toString();
    }

    private JsonNode firstNode(JsonNode[] nodes, String... keys) {
        for (JsonNode node : nodes) {
            if (node == null || node.isMissingNode() || node.isNull()) continue;
            for (String key : keys) {
                JsonNode value = node.path(key);
                if (!value.isMissingNode() && !value.isNull()) return value;
            }
        }
        return null;
    }

    /** Normaliza formatos OpenCode/OTEL para o array OpenAI [{role, content, tool_calls}] do front. */
    private String toOpenAiMessages(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) return null;
        try {
            if (value.isString()) {
                String text = value.asString();
                if (text.isBlank()) return null;
                try {
                    JsonNode parsed = objectMapper.readTree(text);
                    if (parsed.isArray()) return toOpenAiMessages(parsed);
                } catch (Exception ignored) { }
                var arr = objectMapper.createArrayNode();
                var msg = objectMapper.createObjectNode();
                msg.put("role", "user");
                msg.put("content", text);
                arr.add(msg);
                return arr.toString();
            }
            if (value.isArray()) {
                var arr = objectMapper.createArrayNode();
                for (JsonNode item : value) {
                    if (item.isString()) {
                        var msg = objectMapper.createObjectNode();
                        msg.put("role", "user");
                        msg.put("content", item.asString());
                        arr.add(msg);
                    } else if (item.isObject()) {
                        var msg = objectMapper.createObjectNode();
                        JsonNode role = item.path("role");
                        msg.put("role", role.isMissingNode() || role.isNull() ? "user" : role.asString("user"));
                        JsonNode content = item.path("content");
                        if (content.isMissingNode() || content.isNull()) content = item.path("text");
                        if (content.isMissingNode() || content.isNull()) content = item.path("message");
                        msg.put("content", content.isMissingNode() || content.isNull() ? item.toString() : content.isString() ? content.asString() : content.toString());
                        JsonNode toolCalls = item.path("tool_calls");
                        if (toolCalls.isMissingNode()) toolCalls = item.path("toolCalls");
                        if (toolCalls.isArray()) msg.set("tool_calls", toolCalls);
                        JsonNode callId = item.path("tool_call_id");
                        if (callId.isString()) msg.put("tool_call_id", callId.asString());
                        arr.add(msg);
                    }
                }
                return arr.isEmpty() ? null : arr.toString();
            }
            if (value.isObject()) {
                var arr = objectMapper.createArrayNode();
                var msg = objectMapper.createObjectNode();
                msg.put("role", "user");
                msg.put("content", value.toString());
                arr.add(msg);
                return arr.toString();
            }
        } catch (Exception ignored) { }
        return null;
    }

    /** True when the request already shows this text (avoid persisting a duplicate response). */
    private boolean alreadyContains(String requestBody, StringBuilder text) {
        if (requestBody == null || text.length() == 0) return false;
        String probe = text.length() > 500 ? text.substring(0, 500) : text.toString();
        return requestBody.contains(probe);
    }

    private String assistantMessageId(JsonNode... nodes) {
        return firstText(sources(nodes), "assistantMessageID", "assistantMessageId", "messageID", "messageId");
    }

    /**
     * Recompõe a conversa do passo a partir dos irmãos da sessão.
     * `session.step.ended` (kind llm) só traz tokens; o conteúdo está nos
     * eventos `session.text.delta` / `session.reasoning.delta` / `session.tool.*`
     * com o mesmo `assistantMessageID`. Sem isso o front mostra
     * Fluxo 0 / Conversa 0 / Tools 0.
     * Retorna true se preencheu algo. Também normaliza input Tokens para a
     * convenção Anthropic (input inclui cache) em linhas antigas.
     */
    public boolean enrichFromSession(Trace trace, String assistantMessageId) {
        if (trace == null || trace.getSessionId() == null) return false;
        boolean changed = normalizeInputTokens(trace);
        if (trace.getRequestBody() != null && trace.getResponseBody() != null) return changed;
        List<Trace> siblings;
        try {
            siblings = traceRepository.findBySessionIdOrderByStartedAtAsc(trace.getSessionId());
        } catch (RuntimeException ignored) {
            return changed;
        }
        if (siblings == null || siblings.isEmpty()) return changed;

        StringBuilder text = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        Map<String, Map<String, Object>> calls = new LinkedHashMap<>();
        Map<String, String> results = new LinkedHashMap<>();
        for (Trace sibling : siblings) {
            if (sibling == null || sibling.getId() != null && sibling.getId().equals(trace.getId())) continue;
            Map<String, Object> raw = rawData(sibling);
            if (raw == null) continue;
            if (assistantMessageId != null
                    && !assistantMessageId.equals(string(raw.get("assistantMessageID")))) {
                Object alt = raw.get("assistantMessageId");
                if (!assistantMessageId.equals(string(alt))) continue;
            }
            String type = string(siblingEventType(sibling));
            if ("session.text.delta".equals(type)) {
                String delta = string(raw.get("delta"));
                if (delta != null) text.append(delta);
            } else if ("session.reasoning.delta".equals(type)) {
                String delta = string(raw.get("delta"));
                if (delta != null) reasoning.append(delta);
            } else if (type != null && type.startsWith("session.tool.")) {
                String callId = string(raw.get("id"));
                if (type.equals("session.tool.called") || type.equals("session.tool.input.ended")) {
                    if (callId != null) {
                        Map<String, Object> call = calls.computeIfAbsent(callId, k -> new LinkedHashMap<>());
                        Object input = raw.get("input");
                        if (input == null) input = raw.get("text");
                        if (input != null) call.putIfAbsent("args", input);
                    }
                } else if (type.equals("session.tool.success") || type.contains("error")) {
                    if (callId != null) {
                        calls.computeIfAbsent(callId, k -> new LinkedHashMap<>());
                        results.putIfAbsent(callId, toolResultText(raw.get("content")));
                    }
                }
            }
        }

        if (text.length() == 0 && reasoning.length() == 0 && calls.isEmpty()) return changed;
        try {
            var messages = objectMapper.createArrayNode();
            var assistant = objectMapper.createObjectNode();
            assistant.put("role", "assistant");
            String content = text.length() > 0 ? text.toString() : reasoning.toString();
            assistant.put("content", content.length() > 8000 ? content.substring(0, 8000) : content);
            if (!calls.isEmpty()) {
                var toolCalls = objectMapper.createArrayNode();
                for (Map.Entry<String, Map<String, Object>> entry : calls.entrySet()) {
                    var call = objectMapper.createObjectNode();
                    call.put("id", entry.getKey());
                    var fn = objectMapper.createObjectNode();
                    fn.put("name", "tool");
                    Object args = entry.getValue().get("args");
                    if (args != null) {
                        fn.put("arguments", args instanceof String s ? s : objectMapper.valueToTree(args).toString());
                    }
                    call.set("function", fn);
                    toolCalls.add(call);
                }
                assistant.set("tool_calls", toolCalls);
            }
            messages.add(assistant);
            for (Map.Entry<String, String> entry : results.entrySet()) {
                if (entry.getValue() == null) continue;
                var toolMsg = objectMapper.createObjectNode();
                toolMsg.put("role", "tool");
                toolMsg.put("tool_call_id", entry.getKey());
                String result = entry.getValue();
                toolMsg.put("content", result.length() > 4000 ? result.substring(0, 4000) : result);
                messages.add(toolMsg);
            }
            if (trace.getRequestBody() == null) {
                trace.setRequestBody(messages.toString());
                changed = true;
            }
            // The timeline already renders requestBody: persisting the same
            // aggregated text as responseBody shows the answer twice in the UI.
            // Only persist a response that adds information beyond the request.
            if (trace.getResponseBody() == null && text.length() > 0 && !alreadyContains(trace.getRequestBody(), text)) {
                String response = text.toString();
                trace.setResponseBody(response.length() > 8000 ? response.substring(0, 8000) : response);
                changed = true;
            }
        } catch (RuntimeException ignored) {
        }
        return changed;
    }

    /** Normaliza linhas antigas: input passa a incluir o cache (convenção Anthropic). */
    private boolean normalizeInputTokens(Trace trace) {
        if (!"llm".equals(trace.getKind())) return false;
        if (trace.getCacheReadTokens() == null || trace.getCacheReadTokens() <= 0) return false;
        if (trace.getInputTokens() != null && trace.getInputTokens() >= trace.getCacheReadTokens()
                && trace.getTotalTokens() != null
                && trace.getTotalTokens() == trace.getInputTokens() + (trace.getOutputTokens() == null ? 0 : trace.getOutputTokens())) {
            return false; // já normalizado
        }
        int fresh = trace.getInputTokens() == null ? 0 : trace.getInputTokens();
        if (fresh >= trace.getCacheReadTokens() && trace.getTotalTokens() != null
                && trace.getTotalTokens() > trace.getCacheReadTokens()) {
            return false; // input já inclui o cache
        }
        trace.setInputTokens(fresh + trace.getCacheReadTokens());
        trace.setTotalTokens(total(trace.getInputTokens(), trace.getOutputTokens()));
        return true;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> rawData(Trace trace) {
        if (trace.getMetadata() == null) return null;
        Object raw = trace.getMetadata().get("raw");
        if (!(raw instanceof Map<?, ?> rawMap)) return null;
        Object data = rawMap.get("data");
        return data instanceof Map<?, ?> dataMap ? (Map<String, Object>) dataMap : null;
    }

    private String siblingEventType(Trace trace) {
        if (trace.getMetadata() == null) return trace.getName();
        Object type = trace.getMetadata().get("eventType");
        return type instanceof String s ? s : trace.getName();
    }

    private String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String toolResultText(Object content) {
        if (content == null) return null;
        if (content instanceof String s) return s;
        if (content instanceof List<?> list) {
            StringBuilder out = new StringBuilder();
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    Object text = map.get("text");
                    if (text != null) out.append(String.valueOf(text));
                } else if (item != null) {
                    out.append(String.valueOf(item));
                }
            }
            return out.length() == 0 ? null : out.toString();
        }
        return String.valueOf(content);
    }

    private String resolveProject(JsonNode payload, JsonNode properties, JsonNode data, JsonNode info) {
        JsonNode location = node(payload, "location");
        String project = firstText(sources(data, info, properties, payload, location),
                "project", "projectName");
        if (project != null) return project;
        // O hook do OpenCode costuma mandar diretório (inclusive em
        // `payload.location.directory`), não "project".
        String dir = firstText(sources(data, info, properties, payload, location),
                "directory", "cwd", "workingDirectory", "workdir", "root", "path", "filePath");
        if (dir == null) dir = firstText(sources(node(info, "session"), node(data, "session"),
                node(properties, "session"), node(payload, "session")),
                "directory", "cwd", "project", "projectName");
        if (dir == null || dir.isBlank()) return null;
        String clean = dir.replaceAll("[/\\\\]+$", "");
        int slash = Math.max(clean.lastIndexOf('/'), clean.lastIndexOf('\\'));
        String base = slash >= 0 ? clean.substring(slash + 1) : clean;
        if (base.isBlank()) return null;
        // Dot-directories (.ia, .opencode, .cursor) are workspace metadata, never
        // project names: use the parent folder instead. Editor-agnostic, path-only.
        if (base.startsWith(".") && slash > 0) {
            String parent = clean.substring(0, slash).replaceAll("[/\\\\]+$", "");
            int parentSlash = Math.max(parent.lastIndexOf('/'), parent.lastIndexOf('\\'));
            String parentBase = parentSlash >= 0 ? parent.substring(parentSlash + 1) : parent;
            if (!parentBase.isBlank() && !parentBase.startsWith(".")) return parentBase;
            return null; // parent unusable: let session/default correlation decide
        }
        return base;
    }

    private String uniqueId(JsonNode payload, JsonNode properties, JsonNode info) {
        // Só reaproveita id do payload se parecer globalmente único
        // (eventId/uuid). Ids lógicos (messageId/partId) se repetem a cada
        // update e causariam o upsert invisível no front.
        String unique = firstText(sources(payload), "eventID", "eventId", "event_id", "uuid", "spanId", "spanID");
        if (unique == null) {
            String eventId = firstText(payload, "id");
            if (eventId != null && eventId.startsWith("evt_")) unique = eventId;
        }
        if (unique == null) unique = firstText(sources(properties), "eventID", "eventId", "event_id", "uuid");
        if (unique == null) unique = firstText(sources(info), "eventID", "eventId", "event_id", "uuid");
        return unique != null ? unique : UUID.randomUUID().toString();
    }

    private JsonNode node(JsonNode parent, String field) {
        if (parent == null || parent.isMissingNode() || parent.isNull()) return null;
        JsonNode value = parent.path(field);
        return value.isMissingNode() || value.isNull() ? null : value;
    }

    private JsonNode[] sources(JsonNode... nodes) {
        return nodes;
    }

    private String firstText(JsonNode[] nodes, String... keys) {
        for (JsonNode node : nodes) {
            String value = firstText(node, keys);
            if (value != null) return value;
        }
        return null;
    }

    private String firstText(JsonNode node, String... keys) {
        if (node == null || node.isMissingNode() || node.isNull()) return null;
        for (String key : keys) {
            JsonNode value = node.path(key);
            if (value.isValueNode() && !value.asString().isBlank()) return value.asString();
        }
        return null;
    }

    private String text(JsonNode node, String key) { return firstText(node, key); }

    private String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return null;
    }

    private Integer total(Integer a, Integer b) {
        if (a == null && b == null) return null;
        return (a == null ? 0 : a) + (b == null ? 0 : b);
    }
}
