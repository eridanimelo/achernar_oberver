package dev.achernar.observer.dto;

import java.util.List;
import org.springframework.data.domain.Page;

/**
 * Resposta estável da listagem paginada de traces.
 *
 * <p>Serializar {@code Page} direto (PageImpl) gera o WARN
 * "Serializing PageImpl instances as-is is not supported" e o JSON pode mudar
 * entre versões do Spring Data. Este DTO congela o formato que o frontend já
 * consome: {@code content, number, size, totalElements, totalPages, first, last}.
 */
public record TracePageResponse(
        List<TraceSummary> content,
        int number,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last) {

    public static TracePageResponse from(Page<TraceSummary> page) {
        return new TracePageResponse(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isFirst(),
                page.isLast());
    }
}
