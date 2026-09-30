package dev.achernar.observer.service;

import dev.achernar.observer.model.Trace;
import dev.achernar.observer.repository.TraceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;

@Service
public class ProjectResolver {

    private static final Logger log = LoggerFactory.getLogger(ProjectResolver.class);

    private static final String UNKNOWN = "UNKNOWN";

    private final TraceRepository traceRepository;
    private final String defaultProject;

    public ProjectResolver(TraceRepository traceRepository,
                           @Value("${observer.default-project:UNKNOWN}") String defaultProject) {
        this.traceRepository = traceRepository;
        this.defaultProject = defaultProject;
    }

    public String resolve(Trace span) {
        // Precedence: explicit project (set by ingest controller, incl. PROJECT.yaml
        // context fallback) > parent span > trace > session > default > UNKNOWN.
        if (isValid(span.getProject())) {
            return span.getProject();
        }

        if (isValid(span.getParentSpanId())) {
            var parent = traceRepository.findById(span.getParentSpanId());
            if (parent.isPresent() && isValid(parent.get().getProject())) {
                log.debug("Project inherited from parent span");
                return parent.get().getProject();
            }
        }

        if (isValid(span.getTraceId())) {
            var trace = traceRepository.findFirstByTraceIdAndProjectIsNotNullAndProjectNotIgnoreCaseOrderByStartedAtAsc(
                    span.getTraceId(), UNKNOWN);
            if (trace.isPresent() && isValid(trace.get().getProject())) {
                log.debug("Project inherited from trace");
                return trace.get().getProject();
            }
        }

        if (isValid(span.getSessionId())) {
            var session = traceRepository.findFirstBySessionIdAndProjectIsNotNullAndProjectNotIgnoreCaseOrderByStartedAtAsc(
                    span.getSessionId(), UNKNOWN);
            if (session.isPresent() && isValid(session.get().getProject())) {
                log.debug("Project inherited from session");
                return session.get().getProject();
            }
        }

        if (isValid(defaultProject)) {
            log.debug("Project resolved from OBSERVER_DEFAULT_PROJECT");
            return defaultProject;
        }
        log.debug("Project unresolved");
        return UNKNOWN;
    }

    private boolean isValid(String value) {
        return value != null
                && !value.isBlank()
                && !UNKNOWN.equalsIgnoreCase(value);
    }
}
