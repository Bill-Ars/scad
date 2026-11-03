package mz.ac.isutc.scad.agente;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Exclusao mutua distribuida - algoritmo de Ricart-Agrawala.
 * Cada recurso (ex: "F1:P2" = lote do produto P2 ao fornecedor F1) tem a sua propria seccao critica.
 * Pedidos ordenados pelo par (relogio de Lamport, id do no). Mensagens: 2(N-1) por entrada.
 */
@Component
public class RicartAgrawala {

    private static final Logger log = LoggerFactory.getLogger(RicartAgrawala.class);
    private static final long TIMEOUT_MS = 20_000;

    enum Estado { LIVRE, A_PEDIR, DENTRO }

    static class Recurso {
        Estado estado = Estado.LIVRE;
        long ts;                                         // timestamp de Lamport do meu pedido
        final Set<String> aguardaReply = new HashSet<>(); // nos que ainda nao responderam
        final List<String> adiados = new ArrayList<>();   // pedidos a que so respondo ao sair
        int replies;
    }

    private final String regiao;
    private final int meuId;
    private final String meuUrl;
    private final List<String> pares;
    private final MonitorPares monitor;
    private final RestClient http;
    private final AtomicLong lamport = new AtomicLong();
    private final Map<String, Recurso> recursos = new ConcurrentHashMap<>();

    public RicartAgrawala(@Value("${scad.regiao}") String regiao,
                          @Value("${scad.pares}") String pares,
                          MonitorPares monitor) {
        this.regiao = regiao;
        this.meuId = switch (regiao) {
            case "Maputo" -> 1;
            case "Beira" -> 2;
            case "Nampula" -> 3;
            default -> 9;
        };
        this.meuUrl = "http://agente-" + regiao.toLowerCase() + ":8080";
        this.pares = Arrays.stream(pares.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        this.monitor = monitor;
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(Duration.ofSeconds(2));
        f.setReadTimeout(Duration.ofSeconds(3));
        this.http = RestClient.builder().requestFactory(f).build();
    }

    private Recurso rec(String nome) {
        return recursos.computeIfAbsent(nome, k -> new Recurso());
    }

    private boolean vivo(String par) {
        String s = monitor.getEstadoPares().get(par);
        return s == null || s.startsWith("VIVO");
    }

    /** Pede a entrada na seccao critica e bloqueia ate receber REPLY de todos os nos vivos. */
    public Map<String, Object> entrar(String nome) throws InterruptedException {
        Recurso r = rec(nome);
        List<String> destinos = new ArrayList<>();
        List<String> ignorados = new ArrayList<>();
        long ts;
        synchronized (r) {
            if (r.estado != Estado.LIVRE) throw new IllegalStateException("ja estou a pedir ou dentro de " + nome);
            ts = lamport.incrementAndGet();
            r.estado = Estado.A_PEDIR;
            r.ts = ts;
            r.replies = 0;
            for (String p : pares) {
                if (vivo(p)) destinos.add(p); else ignorados.add(p + " (em falha)");
            }
            r.aguardaReply.clear();
            r.aguardaReply.addAll(destinos);
        }
        log.info("[RA {}] {} envia REQUEST(ts={}, id={}) a {}", nome, regiao, ts, meuId, destinos);

        for (String p : destinos) {
            try {
                Map<?, ?> resp = http.post().uri(p + "/ra/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("recurso", nome, "ts", ts, "id", meuId, "origem", meuUrl))
                        .retrieve().body(Map.class);
                if (Boolean.TRUE.equals(resp.get("concedido"))) receberReply(nome, p);   // REPLY imediato
            } catch (Exception e) {
                ignorados.add(p + " (sem resposta)");
                remover(r, p);
            }
        }

        long limite = System.currentTimeMillis() + TIMEOUT_MS;
        synchronized (r) {
            while (!r.aguardaReply.isEmpty()) {
                // tolerancia a falhas: deixa de esperar por nos que o heartbeat deu como mortos
                Iterator<String> it = r.aguardaReply.iterator();
                while (it.hasNext()) {
                    String p = it.next();
                    if (!vivo(p)) { it.remove(); ignorados.add(p + " (falhou durante a espera)"); }
                }
                long falta = limite - System.currentTimeMillis();
                if (falta <= 0) {
                    for (String p : r.aguardaReply) ignorados.add(p + " (timeout)");
                    r.aguardaReply.clear();
                    break;
                }
                if (!r.aguardaReply.isEmpty()) r.wait(Math.min(falta, 1000));
            }
            r.estado = Estado.DENTRO;
        }
        log.info("[RA {}] {} ENTROU na seccao critica", nome, regiao);

        Map<String, Object> info = new LinkedHashMap<>();
        info.put("lamportTs", ts);
        info.put("id", meuId);
        info.put("requestsEnviados", destinos.size());
        info.put("repliesRecebidos", r.replies);
        info.put("ignorados", ignorados);
        return info;
    }

    /** Sai da seccao critica e responde aos pedidos que tinha adiado. */
    public List<String> sair(String nome) {
        Recurso r = rec(nome);
        List<String> adiados;
        synchronized (r) {
            r.estado = Estado.LIVRE;
            adiados = new ArrayList<>(r.adiados);
            r.adiados.clear();
        }
        log.info("[RA {}] {} SAIU e envia REPLY adiado a {}", nome, regiao, adiados);
        for (String p : adiados) {
            try {
                http.post().uri(p + "/ra/reply")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("recurso", nome, "origem", meuUrl))
                        .retrieve().toBodilessEntity();
            } catch (Exception e) {
                log.warn("[RA {}] REPLY a {} falhou", nome, p);
            }
        }
        return adiados;
    }

    /** Recebe um REQUEST. Devolve true = REPLY imediato; false = adiado ate eu sair. */
    public boolean receberRequest(String nome, long ts, int id, String origem) {
        lamport.updateAndGet(c -> Math.max(c, ts) + 1);
        Recurso r = rec(nome);
        synchronized (r) {
            boolean meuPedidoEMaisAntigo = r.estado == Estado.A_PEDIR
                    && (r.ts < ts || (r.ts == ts && meuId < id));
            if (r.estado == Estado.DENTRO || meuPedidoEMaisAntigo) {
                r.adiados.add(origem);
                log.info("[RA {}] {} ADIA o REPLY a {} (estado={}, meu ts={}, ts dele={})",
                        nome, regiao, origem, r.estado, r.ts, ts);
                return false;
            }
            log.info("[RA {}] {} concede REPLY imediato a {}", nome, regiao, origem);
            return true;
        }
    }

    /** Recebe um REPLY (imediato ou adiado). */
    public void receberReply(String nome, String de) {
        Recurso r = rec(nome);
        synchronized (r) {
            if (r.aguardaReply.remove(de)) r.replies++;
            r.notifyAll();
        }
    }

    private void remover(Recurso r, String p) {
        synchronized (r) {
            r.aguardaReply.remove(p);
            r.notifyAll();
        }
    }
}
