package dev.achernar.observer.repository;

import dev.achernar.observer.dto.TraceSummary;
import dev.achernar.observer.model.Trace;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

@Repository
public class TraceQueryRepositoryImpl implements TraceQueryRepository {

    private final EntityManager entityManager;

    public TraceQueryRepositoryImpl(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public Page<TraceSummary> findSummaryPage(
            Instant from,
            Instant to,
            String project,
            boolean technical,
            String search,
            Pageable pageable) {

        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<TraceSummary> query = cb.createQuery(TraceSummary.class);
        Root<Trace> root = query.from(Trace.class);

        query.select(cb.construct(
                TraceSummary.class,
                root.get("id"), root.get("traceId"), root.get("project"), root.get("parentSpanId"),
                root.get("sessionId"), root.get("name"), root.get("model"), root.get("agent"),
                root.get("kind"), root.get("startedAt"), root.get("durationMs"), root.get("inputTokens"),
                root.get("outputTokens"), root.get("cacheReadTokens"), root.get("cacheWriteTokens"),
                root.get("totalTokens"), root.get("status"), root.get("error")));

        List<Predicate> predicates = predicates(cb, root, from, to, project, technical, search);
        query.where(predicates.toArray(Predicate[]::new));
        query.orderBy(cb.desc(root.get("startedAt")));

        TypedQuery<TraceSummary> typedQuery = entityManager.createQuery(query);
        typedQuery.setFirstResult((int) pageable.getOffset());
        typedQuery.setMaxResults(pageable.getPageSize());
        List<TraceSummary> content = typedQuery.getResultList();

        CriteriaQuery<Long> countQuery = cb.createQuery(Long.class);
        Root<Trace> countRoot = countQuery.from(Trace.class);
        countQuery.select(cb.count(countRoot));
        List<Predicate> countPredicates = predicates(cb, countRoot, from, to, project, technical, search);
        countQuery.where(countPredicates.toArray(Predicate[]::new));
        long total = entityManager.createQuery(countQuery).getSingleResult();

        return new PageImpl<>(content, pageable, total);
    }

    private List<Predicate> predicates(
            CriteriaBuilder cb,
            Root<Trace> root,
            Instant from,
            Instant to,
            String project,
            boolean technical,
            String search) {

        List<Predicate> predicates = new ArrayList<>();

        if (from != null) {
            predicates.add(cb.greaterThanOrEqualTo(root.get("startedAt"), from));
        }
        if (to != null) {
            predicates.add(cb.lessThanOrEqualTo(root.get("startedAt"), to));
        }
        if (project != null && !project.isBlank()) {
            predicates.add(cb.equal(cb.lower(root.get("project")), project.trim().toLowerCase(Locale.ROOT)));
        }
        if (!technical) {
            predicates.add(cb.equal(cb.lower(root.get("kind")), "llm"));
        }
        if (search != null && !search.isBlank()) {
            String pattern = "%" + search.trim().toLowerCase(Locale.ROOT) + "%";
            predicates.add(cb.or(
                    like(cb, root.get("name"), pattern),
                    like(cb, root.get("model"), pattern),
                    like(cb, root.get("project"), pattern),
                    like(cb, root.get("agent"), pattern),
                    like(cb, root.get("traceId"), pattern)));
        }
        return predicates;
    }

    private Predicate like(CriteriaBuilder cb, Expression<String> expression, String pattern) {
        return cb.like(cb.lower(cb.coalesce(expression, "")), pattern);
    }
}
