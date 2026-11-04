package mz.ac.isutc.scad.agente;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Detecção de falhas: pergunta periodicamente aos outros nós se estão vivos (heartbeat). */
@Component
public class MonitorPares {

    private static final Logger log = LoggerFactory.getLogger(MonitorPares.class);

    private final String regiao;
    private final List<String> pares;                       // ex: http://agente-beira:8080
    private final RestClient http;
    private final Map<String, String> estadoPares = new ConcurrentHashMap<>();

    public MonitorPares(@Value("${scad.regiao}") String regiao,
                        @Value("${scad.pares}") String pares) {
        this.regiao = regiao;
        this.pares = Arrays.stream(pares.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();

        // Timeout de 2 s: se um nó não responder neste tempo, é considerado EM FALHA
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(Duration.ofSeconds(2));
        
        f.setReadTimeout(Duration.ofSeconds(2));
        this.http = RestClient.builder().requestFactory(f).build();
    }

    @Scheduled(fixedRate = 5000)                            // de 5 em 5 segundos
    public void verificar() {
        for (String par : pares) {
            String anterior = estadoPares.get(par);
            String atual;
            try {
                Map<?, ?> resp = http.get().uri(par + "/estado").retrieve().body(Map.class);
                atual = "VIVO (" + resp.get("regiao") + ")";
            } catch (Exception e) {
                atual = "EM FALHA";
            }
            estadoPares.put(par, atual);
            if (!atual.equals(anterior)) {                  // só regista quando o estado muda
                log.info("[{}] {} -> {}", regiao, par, atual);
            }
        }
    }

    public Map<String, String> getEstadoPares() {
        return estadoPares;
    }
}