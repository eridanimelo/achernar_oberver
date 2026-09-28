package dev.achernar.observer.dto;

import java.time.Instant;

public record TraceSummary(
        String id,
        String traceId,
        String project,
        String parentSpanId,
        String sessionId,
        String name,
        String model,
        String agent,
        String kind,
        Instant startedAt,
        long durationMs,
        Integer inputTokens,
        Integer outputTokens,
        Integer cacheReadTokens,
        Integer cacheWriteTokens,
        Integer totalTokens,
        Integer status,
        String error) {
}
