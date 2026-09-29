package dev.achernar.observer.service;

import dev.achernar.observer.model.Trace;
import dev.achernar.observer.repository.TraceRepository;
import org.springframework.stereotype.Service;

@Service
public class TelemetryIngestionService {
    private final TraceRepository repository;
    private final ProjectResolver projectResolver;
    private final LiveTelemetryService liveTelemetry;

    public TelemetryIngestionService(TraceRepository repository,
                                     ProjectResolver projectResolver,
                                     LiveTelemetryService liveTelemetry) {
        this.repository = repository;
        this.projectResolver = projectResolver;
        this.liveTelemetry = liveTelemetry;
    }

    public Trace ingest(Trace trace, String notificationType) {
        if (trace == null) return null;
        trace.setProject(projectResolver.resolve(trace));
        Trace saved = repository.save(trace);
        liveTelemetry.publish(notificationType == null || notificationType.isBlank() ? "telemetry" : notificationType);
        return saved;
    }
}
