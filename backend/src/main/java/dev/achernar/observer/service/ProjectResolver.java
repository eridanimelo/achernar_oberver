package dev.achernar.observer.service;

import dev.achernar.observer.model.Trace;
import dev.achernar.observer.repository.TraceRepository;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;

@Service
public class ProjectResolver {

    private static final String UNKNOWN = "UNKNOWN";

    private final TraceRepository traceRepository;
    private final String defaultProject;

    public ProjectResolver(TraceRepository traceRepository,
                           @Value("${observer.default-project:UNKNOWN}") String defaultProject) {
        this.traceRepository = traceRepository;
        this.defaultProject = defaultProject;
    }

    public String resolve(Trace span) {
        if (isValid(span.getProject())) {
            return span.getProject();
        }

        if (isValid(span.getParentSpanId())) {
            var parent = traceRepository.findById(span.getParentSpanId());
            if (parent.isPresent() && isValid(parent.get().getProject())) {
                return parent.get().getProject();
            }
        }

        if (isValid(span.getTraceId())) {
            var trace = traceRepository.findFirstByTraceIdAndProjectIsNotNullOrderByStartedAtAsc(span.getTraceId());
            if (trace.isPresent() && isValid(trace.get().getProject())) {
                return trace.get().getProject();
            }
        }

        if (isValid(span.getSessionId())) {
            var session = traceRepository.findFirstBySessionIdAndProjectIsNotNullOrderByStartedAtAsc(span.getSessionId());
            if (session.isPresent() && isValid(session.get().getProject())) {
                return session.get().getProject();
            }
        }

        return isValid(defaultProject) ? defaultProject : UNKNOWN;
    }

    private boolean isValid(String value) {
        return value != null
                && !value.isBlank()
                && !UNKNOWN.equalsIgnoreCase(value);
    }
}
