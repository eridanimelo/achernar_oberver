package dev.achernar.observer.repository;

import dev.achernar.observer.dto.TraceSummary;
import java.time.Instant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface TraceQueryRepository {
    Page<TraceSummary> findSummaryPage(
            Instant from,
            Instant to,
            String project,
            boolean technical,
            String search,
            Pageable pageable);
}
