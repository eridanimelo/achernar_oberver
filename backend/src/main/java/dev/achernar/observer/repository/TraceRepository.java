package dev.achernar.observer.repository;

import dev.achernar.observer.model.Trace;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TraceRepository extends JpaRepository<Trace, String>, TraceQueryRepository {

    List<Trace> findTop2000ByOrderByStartedAtDesc();
    List<Trace> findByTraceIdOrderByStartedAtAsc(String traceId);
    List<Trace> findByStartedAtBetweenOrderByStartedAtAsc(Instant from, Instant to);
    List<Trace> findByStartedAtGreaterThanEqualOrderByStartedAtAsc(Instant from);
    List<Trace> findByStartedAtLessThanEqualOrderByStartedAtAsc(Instant to);
    // Correlation must skip placeholder rows: the first span of a trace is often a
    // context-less http/auth span persisted as UNKNOWN, which must not poison
    // later siblings that carry the real project.
    Optional<Trace> findFirstByTraceIdAndProjectIsNotNullAndProjectNotIgnoreCaseOrderByStartedAtAsc(
            String traceId, String excludedProject);
    Optional<Trace> findFirstBySessionIdAndProjectIsNotNullAndProjectNotIgnoreCaseOrderByStartedAtAsc(
            String sessionId, String excludedProject);
    Optional<Trace> findFirstBySessionIdAndModelIsNotNullOrderByStartedAtDesc(String sessionId);
    List<Trace> findBySessionIdOrderByStartedAtAsc(String sessionId);
    // Deduplicação OTEL x OpenCode: o mesmo passo do modelo chega duas vezes
    // (span LiteLLM via OTEL + session.step.ended via plugin) com mesma sessão
    // e timestamps próximos. Os totais podem divergir (OTEL soma reasoning no
    // output; o plugin separa output/reasoning), então filtra por sessão+janela
    // e o serviço decide pelo input + texto da resposta.
    List<Trace> findBySessionIdAndKindAndStartedAtBetween(
            String sessionId, String kind, Instant from, Instant to);

    @Query(value = """
        select coalesce(project, 'UNKNOWN') as project,
          coalesce(sum(total_tokens), 0), coalesce(sum(input_tokens), 0),
          coalesce(sum(output_tokens), 0), coalesce(sum(cache_read_tokens), 0),
          coalesce(sum(cache_write_tokens), 0), count(distinct session_id),
          count(*) filter (where lower(kind) = 'llm')
        from observer_trace
        where (cast(:from as timestamptz) is null or started_at >= cast(:from as timestamptz))
          and (cast(:to as timestamptz) is null or started_at <= cast(:to as timestamptz))
        group by coalesce(project, 'UNKNOWN')
        order by coalesce(sum(total_tokens), 0) desc
        """, nativeQuery = true)
    List<Object[]> projectAggregates(@Param("from") Instant from, @Param("to") Instant to);

    @Query(value = """
        select date_trunc(:bucket, started_at) as bucket_at,
          coalesce(sum(total_tokens), 0), coalesce(sum(input_tokens), 0),
          coalesce(sum(output_tokens), 0), coalesce(sum(cache_read_tokens), 0),
          coalesce(sum(cache_write_tokens), 0)
        from observer_trace
        where started_at >= :from and started_at <= :to
          and (:project = '' or lower(project) = :project)
        group by 1
        order by bucket_at
        """, nativeQuery = true)
    List<Object[]> timeSeries(
            @Param("from") Instant from, @Param("to") Instant to,
            @Param("project") String project, @Param("bucket") String bucket);

    @Query(value = """
        select
          count(*) as spans,
          coalesce(sum(total_tokens), 0) as tokens,
          coalesce(sum(input_tokens), 0) as input_tokens,
          coalesce(sum(output_tokens), 0) as output_tokens,
          coalesce(sum(cache_read_tokens), 0) as cache_read_tokens,
          coalesce(sum(cache_write_tokens), 0) as cache_write_tokens,
          count(distinct session_id) as sessions,
          count(distinct trace_id) as traces,
          count(*) filter (where lower(kind) = 'llm') as llm_calls,
          coalesce(avg(duration_ms) filter (where lower(kind) = 'llm'), 0) as avg_duration_ms,
          count(distinct agent) filter (where agent is not null) as agents
        from observer_trace
        where (cast(:from as timestamptz) is null or started_at >= cast(:from as timestamptz))
          and (cast(:to as timestamptz) is null or started_at <= cast(:to as timestamptz))
          and (:project = '' or lower(project) = :project)
        """, nativeQuery = true)
    List<Object[]> aggregate(
            @Param("from") Instant from,
            @Param("to") Instant to,
            @Param("project") String project);

}
