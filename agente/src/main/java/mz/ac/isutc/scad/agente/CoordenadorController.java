package mz.ac.isutc.scad.agente;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** COORDENADOR do 2PC: o agente que inicia uma compra multi-armazem coordena a transacao. */
@RestController
public class CoordenadorController {

    private static final Logger log = LoggerFactory.getLogger(CoordenadorController.class);

    private final JdbcTemplate jdbc;
    private final String regiao;
    private final RestClient http;
    private final Relogio relogio;

    public CoordenadorController(JdbcTemplate jdbc, Relogio relogio, @Value("${scad.regiao}") String regiao) {
        this.jdbc = jdbc;
        this.relogio = relogio;
        this.regiao = regiao;
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(Duration.ofSeconds(3));
        f.setReadTimeout(Duration.ofSeconds(3));
        this.http = RestClient.builder().requestFactory(f).build();
    }

    private String urlDe(String r) {
        return "http://agente-" + r.toLowerCase() + ":8080";
    }

    @PostMapping("/compras/multi")
    public Map<String, Object> compraMulti(@RequestBody Map<String, Object> pedido) {
        String produtoId = (String) pedido.get("produtoId");
        String fornecedorId = (String) pedido.get("fornecedorId");
        @SuppressWarnings("unchecked")
        Map<String, Object> quantidades = (Map<String, Object>) pedido.get("quantidades");
        boolean simularFalha = Boolean.TRUE.equals(pedido.get("simularFalhaCoordenador"));

        long t = System.currentTimeMillis();
        String base = "compra-" + t;
        String ordemId = "ORD-" + t;
        List<String> regioes = new ArrayList<>(quantidades.keySet());

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("coordenador", regiao);
        r.put("transacao", base);
        r.put("ordemId", ordemId);
        r.put("participantes", regioes);
        registar("2PC-INICIO", base + " participantes=" + String.join(",", regioes));

        // ---------- FASE 1: votacao ----------
        Map<String, Object> votos = new LinkedHashMap<>();
        boolean todosSim = true;
        for (String reg : regioes) {
            Map<String, Object> corpo = new LinkedHashMap<>();
            corpo.put("gid", base + "-" + reg.toLowerCase());
            corpo.put("ordemId", ordemId);
            corpo.put("produtoId", produtoId);
            corpo.put("fornecedorId", fornecedorId);
            corpo.put("quantidade", quantidades.get(reg));
            String voto;
            try {
                Map<?, ?> resp = http.post().uri(urlDe(reg) + "/tx/preparar")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(corpo).retrieve().body(Map.class);
                voto = String.valueOf(resp.get("voto"));
                if (!"SIM".equals(voto)) voto = "NAO (" + resp.get("motivo") + ")";
            } catch (Exception e) {
                voto = "NAO (sem resposta: participante em falha)";
            }
            votos.put(reg, voto);
            if (!"SIM".equals(voto)) todosSim = false;
            log.info("[2PC {}] FASE 1 - {} votou {}", base, reg, voto);
        }
        r.put("fase1_votos", votos);

        // ---------- DECISAO: gravada ANTES da fase 2 ----------
        String decisao = todosSim ? "COMMIT" : "ABORT";
        registar("2PC-DECISAO", base + " " + decisao + " " + String.join(",", regioes));
        r.put("decisao", decisao);
        log.info("[2PC {}] DECISAO = {}", base, decisao);

        if (simularFalha) {
            r.put("fase2", "NAO EXECUTADA: falha simulada do coordenador depois da decisao");
            log.warn("[2PC {}] coordenador 'caiu' antes da fase 2", base);
            return r;
        }

        // ---------- FASE 2: execucao da decisao ----------
        r.put("fase2_resultados", fase2(base, decisao, regioes, votos));
        return r;
    }

    private Map<String, Object> fase2(String base, String decisao, List<String> regioes,
                                      Map<String, Object> votos) {
        String acao = "COMMIT".equals(decisao) ? "confirmar" : "abortar";
        Map<String, Object> res = new LinkedHashMap<>();
        for (String reg : regioes) {
            if (votos != null && !"SIM".equals(votos.get(reg))) {
                res.put(reg, "nada a fazer (votou NAO e ja desfez localmente)");
                continue;
            }
            try {
                Map<?, ?> resp = http.post()
                        .uri(urlDe(reg) + "/tx/" + acao + "/" + base + "-" + reg.toLowerCase())
                        .retrieve().body(Map.class);
                Object motivo = resp.get("motivo");
                res.put(reg, resp.get("estado") + (motivo != null ? " (" + motivo + ")" : ""));
            } catch (Exception e) {
                res.put(reg, "SEM RESPOSTA: repetir mais tarde");
            }
            log.info("[2PC {}] FASE 2 - {} {} -> {}", base, acao, reg, res.get(reg));
        }
        registar("2PC-FIM", base + " " + decisao);
        return res;
    }

    /** Recuperacao do coordenador: le a decisao gravada na auditoria e termina a fase 2. */
    @PostMapping("/compras/multi/{base}/recuperar")
    public Map<String, Object> recuperar(@PathVariable String base) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("transacao", base);
        List<String> linhas = jdbc.queryForList(
                "SELECT detalhe FROM auditoria WHERE operacao = '2PC-DECISAO' AND detalhe LIKE ? " +
                "ORDER BY id DESC LIMIT 1", String.class, base + " %");
        if (linhas.isEmpty()) {
            r.put("decisaoRegistada", "nenhuma");
            return r;
        }
        String[] partes = linhas.get(0).split(" ");       // base decisao regiao1,regiao2
        String decisao = partes[1];
        List<String> regioes = Arrays.asList(partes[2].split(","));
        r.put("decisaoRegistada", decisao);
        r.put("fase2_resultados", fase2(base, decisao, regioes, null));
        return r;
    }

    private void registar(String operacao, String detalhe) {
        // hora sincronizada pelo algoritmo de Berkeley
        jdbc.update("INSERT INTO auditoria (no_origem, hora, operacao, detalhe) VALUES (?, ?, ?, ?)",
                "agente-" + regiao.toLowerCase(), java.sql.Timestamp.from(relogio.agora()), operacao, detalhe);
    }
}
