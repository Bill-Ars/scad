package mz.ac.isutc.scad.agente;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Algoritmo de Berkeley.
 * O no MESTRE (scad.berkeley-mestre=true) pergunta a hora aos outros, compensa o tempo de ida e volta,
 * calcula a media das diferencas (ignorando valores absurdos) e envia a cada no o AJUSTE a aplicar.
 */
@RestController
@RequestMapping("/relogio")
public class BerkeleyController {

    private static final Logger log = LoggerFactory.getLogger(BerkeleyController.class);
    private static final long LIMITE_OUTLIER_MS = 10 * 60 * 1000;     // diferencas > 10 min sao ignoradas

    private final Relogio relogio;
    private final String regiao;
    private final boolean mestre;
    private final List<String> pares;
    private final RestClient http;

    public BerkeleyController(Relogio relogio,
                              @Value("${scad.regiao}") String regiao,
                              @Value("${scad.berkeley-mestre:false}") boolean mestre,
                              @Value("${scad.pares}") String pares) {
        this.relogio = relogio;
        this.regiao = regiao;
        this.mestre = mestre;
        this.pares = Arrays.stream(pares.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(Duration.ofSeconds(2));
        f.setReadTimeout(Duration.ofSeconds(2));
        this.http = RestClient.builder().requestFactory(f).build();
    }

    /** Hora local deste no. */
    @GetMapping
    public Map<String, Object> hora() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("regiao", regiao);
        r.put("hora", relogio.agora().toString());
        r.put("epochMs", relogio.agora().toEpochMilli());
        r.put("desvioMs", relogio.getDesvioMs());
        r.put("mestreBerkeley", mestre);
        return r;
    }

    /** Escravo: aplica o ajuste enviado pelo mestre. */
    @PostMapping("/ajustar")
    public Map<String, Object> ajustar(@RequestBody Map<String, Object> corpo) {
        long ajuste = ((Number) corpo.get("ajusteMs")).longValue();
        relogio.ajustar(ajuste);
        log.info("[Berkeley] {} aplicou ajuste de {} ms", regiao, ajuste);
        return hora();
    }

    /** Volta a desacertar o relogio (para repetir a demonstracao). */
    @PostMapping("/desacertar")
    public Map<String, Object> desacertar() {
        relogio.repor();
        return hora();
    }

    /** Mestre: executa uma ronda do algoritmo de Berkeley. */
    @PostMapping("/sincronizar")
    public Map<String, Object> sincronizar() {
        Map<String, Object> r = new LinkedHashMap<>();
        if (!mestre) {
            r.put("erro", "este no nao e o mestre de Berkeley");
            return r;
        }
        // 1. Recolher as horas (o mestre conta com diferenca 0)
        Map<String, Long> diferencas = new LinkedHashMap<>();
        Map<String, String> enderecos = new LinkedHashMap<>();
        Map<String, Object> ignorados = new LinkedHashMap<>();
        diferencas.put(regiao, 0L);
        for (String par : pares) {
            try {
                long t0 = System.nanoTime();
                Map<?, ?> resp = http.get().uri(par + "/relogio").retrieve().body(Map.class);
                long rttMs = (System.nanoTime() - t0) / 1_000_000;
                long horaPar = ((Number) resp.get("epochMs")).longValue() + rttMs / 2;   // compensa a viagem
                long dif = horaPar - relogio.agora().toEpochMilli();
                String nome = String.valueOf(resp.get("regiao"));
                if (Math.abs(dif) > LIMITE_OUTLIER_MS) {
                    ignorados.put(nome, "diferenca absurda (" + dif + " ms)");
                    continue;
                }
                diferencas.put(nome, dif);
                enderecos.put(nome, par);
            } catch (Exception e) {
                ignorados.put(par, "sem resposta");
            }
        }
        // 2. Media das diferencas
        long media = Math.round(diferencas.values().stream().mapToLong(Long::longValue).average().orElse(0));
        // 3. Enviar a cada no o seu ajuste (media - diferenca)
        Map<String, Long> ajustes = new LinkedHashMap<>();
        for (Map.Entry<String, Long> e : diferencas.entrySet()) {
            long ajuste = media - e.getValue();
            ajustes.put(e.getKey(), ajuste);
            if (e.getKey().equals(regiao)) {
                relogio.ajustar(ajuste);
            } else {
                try {
                    http.post().uri(enderecos.get(e.getKey()) + "/relogio/ajustar")
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(Map.of("ajusteMs", ajuste)).retrieve().toBodilessEntity();
                } catch (Exception ex) {
                    ignorados.put(e.getKey(), "ajuste nao entregue");
                }
            }
        }
        log.info("[Berkeley] diferencas={} media={} ajustes={}", diferencas, media, ajustes);
        r.put("mestre", regiao);
        r.put("diferencasMs", diferencas);
        r.put("mediaMs", media);
        r.put("ajustesMs", ajustes);
        r.put("ignorados", ignorados);
        return r;
    }
}
