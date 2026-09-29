package dev.achernar.observer.telemetry;

import dev.achernar.observer.model.Trace;

/** Converts a source-specific telemetry payload into ACHERNAR's canonical Trace model. */
public interface TelemetryAdapter<T> {
    Trace normalize(T payload);
}
