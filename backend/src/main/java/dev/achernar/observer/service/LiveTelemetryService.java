package dev.achernar.observer.service;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class LiveTelemetryService {
    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(0L);
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(error -> emitters.remove(emitter));
        try { emitter.send(SseEmitter.event().name("connected").data("ok")); }
        catch (Exception error) { emitters.remove(emitter); }
        return emitter;
    }

    public void publish(String type) {
        // Nunca pode lançar: é chamado dentro da ingestão OTLP. Um cliente SSE
        // desconectado (reload do browser, proxy, timeout) não pode derrubar
        // nem atrasar o lote — e o emitter morto precisa sair da lista.
        for (SseEmitter emitter : List.copyOf(emitters)) {
            try {
                emitter.send(SseEmitter.event().name("trace").data(type));
            } catch (Exception sendError) {
                dropQuietly(emitter);
            } catch (Throwable fatal) {
                dropQuietly(emitter);
            }
        }
    }

    private void dropQuietly(SseEmitter emitter) {
        emitters.remove(emitter);
        try {
            emitter.complete();
        } catch (Exception ignored) {
            // AsyncContext já encerrado pelo container — nada a fazer.
        } catch (Throwable ignored) {
            // Idem: publish nunca propaga erro para a ingestão.
        }
    }
}
