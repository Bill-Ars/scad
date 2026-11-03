package mz.ac.isutc.scad.agente;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Relogio fisico do no. Todos os contentores partilham o relogio do mesmo kernel,
 * por isso cada no tem um DESVIO SIMULADO (scad.desvio-s) que o algoritmo de Berkeley corrige.
 */
@Component
public class Relogio {

    private final long desvioInicialMs;
    private final AtomicLong desvioMs;

    public Relogio(@Value("${scad.desvio-s:0}") long desvioS) {
        this.desvioInicialMs = desvioS * 1000;
        this.desvioMs = new AtomicLong(desvioInicialMs);
    }

    /** Hora deste no = hora real + desvio (deriva simulada + ajustes do Berkeley). */
    public Instant agora() {
        return Instant.now().plusMillis(desvioMs.get());
    }

    public long getDesvioMs() {
        return desvioMs.get();
    }

    public void ajustar(long ajusteMs) {
        desvioMs.addAndGet(ajusteMs);
    }

    /** Volta ao desvio inicial (para repetir a demonstracao). */
    public void repor() {
        desvioMs.set(desvioInicialMs);
    }
}
