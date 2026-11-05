package mz.ac.isutc.scad.agente;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * API do painel web (index.html). O navegador fala apenas com este agente;
 * e o agente que consulta os outros nos, o HDFS e os fornecedores (evita problemas de CORS).
 */
@RestController
@RequestMapping("/painel")
public class PainelController {

    private final JdbcTemplate jdbc;
    private final String regiao;
    private final List<String> pares;
    private final EstadoController estado;
    private final StockController stock;
    private final BerkeleyController berkeley;
    private final HdfsCliente hdfs;
    private final OperacaoController operacao;
    private final RestClient http;
    private final RestClient httpLento;

    private static final Set<String> REGIOES = Set.of("Maputo", "Beira", "Nampula");
    private static final int LINHAS_AUDITORIA = 40;
    private static final int LINHAS_MOVIMENTOS = 40;
    private static final int LINHAS_ORDENS = 60;
    private static final int LINHAS_FATURAS = 60;
    private static final Comparator<Map<String, Object>> MAIS_RECENTE_PRIMEIRO =
            Comparator.comparing((Map<String, Object> a) -> String.valueOf(a.get("hora")), Comparator.reverseOrder());

    public PainelController(JdbcTemplate jdbc, @Value("${scad.regiao}") String regiao,
                            @Value("${scad.pares}") String pares, EstadoController estado,
                            StockController stock, BerkeleyController berkeley, HdfsCliente hdfs,
                            OperacaoController operacao) {
        this.jdbc = jdbc;
        this.regiao = regiao;
        this.pares = Arrays.stream(pares.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        this.estado = estado;
        this.stock = stock;
        this.berkeley = berkeley;
        this.hdfs = hdfs;
        this.operacao = operacao;
        this.http = cliente(2);
        this.httpLento = cliente(90);
    }

    private static RestClient cliente(int segundos) {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(Duration.ofSeconds(2));
        f.setReadTimeout(Duration.ofSeconds(segundos));
        return RestClient.builder().requestFactory(f).build();
    }

    private static String nomeDe(String url) {
        String s = url.replace("http://agente-", "").replace(":8080", "");
        return s.substring(0, 1).toUpperCase() + s.substring(1);
    }

    @GetMapping("/resumo")
    public Map<String, Object> resumo() {
        Map<String, Object> r = new LinkedHashMap<>();

        // Nos regionais: estado, base de dados e relogio
        List<Map<String, Object>> nos = new ArrayList<>();
        Map<String, Object> eu = new LinkedHashMap<>(estado.estado());
        eu.putAll(berkeley.hora());
        eu.putAll(operacao.simulacao());
        eu.put("online", true);
        nos.add(eu);
        for (String p : pares) {
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("regiao", nomeDe(p));
            try {
                n.putAll(http.get().uri(p + "/estado").retrieve().body(Map.class));
                n.putAll(http.get().uri(p + "/relogio").retrieve().body(Map.class));
                n.putAll(http.get().uri(p + "/simulacao").retrieve().body(Map.class));
                n.put("online", true);
            } catch (Exception e) {
                n.put("online", false);
            }
            nos.add(n);
        }
        r.put("nos", nos);

        // Stock global (reconstrucao dos fragmentos)
        r.put("stock", stock.stockGlobal());

        r.put("catalogoFornecedores", fornecedoresDisponiveis());

        // Ordens de compra de todas as regioes: os totais vem contados de cada fragmento
        List<Map<String, Object>> respostas = new ArrayList<>();
        respostas.add(ordens());
        for (String p : pares) {
            try { respostas.add(http.get().uri(p + "/painel/ordens").retrieve().body(Map.class)); }
            catch (Exception ignorado) { }
        }
        List<Object> ordens = new ArrayList<>();
        Map<String, Long> porEstado = new LinkedHashMap<>();
        for (Map<String, Object> resposta : respostas) {
            ordens.addAll((List<?>) resposta.get("ordens"));
            ((Map<?, ?>) resposta.get("porEstado")).forEach((estado, total) ->
                    porEstado.merge(String.valueOf(estado), ((Number) total).longValue(), Long::sum));
        }
        r.put("ordens", ordens);
        r.put("ordensPorEstado", porEstado);

        // Vendas, entradas e entregas de todas as regioes
        List<Map<String, Object>> movimentos = new ArrayList<>(movimentos());
        for (String p : pares) {
            try { movimentos.addAll(http.get().uri(p + "/painel/movimentos").retrieve().body(List.class)); }
            catch (Exception ignorado) { }
        }
        movimentos.sort(MAIS_RECENTE_PRIMEIRO);
        r.put("movimentos", movimentos.stream().limit(LINHAS_MOVIMENTOS).toList());

        // HDFS
        Map<String, Object> dfs = new LinkedHashMap<>();
        try {
            Map<?, ?> jmx = http.get()
                    .uri("http://namenode:9870/jmx?qry=Hadoop:service=NameNode,name=FSNamesystemState")
                    .retrieve().body(Map.class);
            Map<?, ?> bean = (Map<?, ?>) ((List<?>) jmx.get("beans")).get(0);
            dfs.put("vivos", bean.get("NumLiveDataNodes"));
            dfs.put("mortos", bean.get("NumDeadDataNodes"));
            dfs.put("stale", bean.get("NumStaleDataNodes"));
            dfs.put("online", true);
        } catch (Exception e) {
            dfs.put("online", false);
        }
        List<Map<String, Object>> faturas = listarDfs("/scad/faturas");
        dfs.put("faturasTotal", faturas.size());
        dfs.put("faturas", faturas.subList(Math.max(0, faturas.size() - LINHAS_FATURAS), faturas.size()));
        dfs.put("relatorios", listarDfs("/scad/auditoria"));
        dfs.put("logs", listarDfs("/scad/logs"));
        r.put("hdfs", dfs);

        // Fornecedores
        Map<String, Object> forn = new LinkedHashMap<>();
        try {
            http.get().uri("http://fornecedor-rest:8080/api/ordens").retrieve().toBodilessEntity();
            forn.put("restOnline", true);
        } catch (Exception e) {
            forn.put("restOnline", false);
        }
        try {
            http.get().uri("http://fornecedor-soap:8080/ws?wsdl").retrieve().toBodilessEntity();
            forn.put("soapOnline", true);
        } catch (Exception e) {
            forn.put("soapOnline", false);
        }
        r.put("fornecedores", forn);

        // Auditoria de todas as regioes, ordenada pela hora Berkeley
        List<Map<String, Object>> auditoria = new ArrayList<>(auditoria());
        for (String p : pares) {
            try { auditoria.addAll(http.get().uri(p + "/painel/auditoria").retrieve().body(List.class)); }
            catch (Exception ignorado) { }
        }
        auditoria.sort(MAIS_RECENTE_PRIMEIRO);
        r.put("auditoria", auditoria.stream().limit(LINHAS_AUDITORIA).toList());
        return r;
    }

    private List<Map<String, Object>> listarDfs(String pasta) {
        try { return hdfs.listar(pasta); }
        catch (Exception e) { return List.of(); }      // pasta ainda vazia ou HDFS em baixo
    }

    private List<Map<String, Object>> fornecedoresDisponiveis() {
        try {
            return jdbc.queryForList("SELECT id, nome, tipo_integracao FROM fornecedor ORDER BY id");
        } catch (Exception e) {
            return List.of();
        }
    }

    /** Auditoria recente do no local. As vendas dos clientes simulados ficam na base de dados, mas nao enchem o painel. */
    @GetMapping("/auditoria")
    public List<Map<String, Object>> auditoria() {
        return registosAuditoria("operacao <> 'VENDA-AUTO'", LINHAS_AUDITORIA);
    }

    /** Historico recente de vendas, entradas e entregas do no local. */
    @GetMapping("/movimentos")
    public List<Map<String, Object>> movimentos() {
        return registosAuditoria("operacao IN ('VENDA', 'VENDA-AUTO', 'ENTRADA', 'ENTREGA')", LINHAS_MOVIMENTOS);
    }

    private List<Map<String, Object>> registosAuditoria(String filtro, int limite) {
        try {
            return jdbc.queryForList(
                    "SELECT to_char(hora AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS.MS') AS hora, no_origem, operacao, detalhe " +
                    "FROM auditoria WHERE " + filtro + " ORDER BY id DESC LIMIT " + limite);
        } catch (Exception e) {
            return List.of();
        }
    }

    /** Pede a todos os nos que exportem ja os relatorios de auditoria para o HDFS. */
    @PostMapping("/auditoria/exportar")
    public Map<String, Object> exportarAuditoria() {
        List<String> semResposta = new ArrayList<>();
        for (String p : todosOsNos()) {
            try { httpLento.post().uri(p + "/dfs/auditoria/exportar").retrieve().toBodilessEntity(); }
            catch (Exception e) { semResposta.add(nomeDe(p)); }
        }
        return Map.of("semResposta", semResposta);
    }

    private List<String> todosOsNos() {
        List<String> nos = new ArrayList<>(pares);
        nos.add("http://agente-" + regiao.toLowerCase() + ":8080");
        return nos;
    }

    /** Venda ou entrada de stock na regiao escolhida: so o agente dessa regiao escreve no seu fragmento. */
    @PostMapping("/movimento")
    public ResponseEntity<String> movimento(@RequestParam String regiao, @RequestParam String produtoId,
                                            @RequestParam String tipo, @RequestParam int quantidade) {
        if (!REGIOES.contains(regiao)) return erroJson(HttpStatus.BAD_REQUEST, "regiao invalida");
        try {
            return http.post().uri("http://agente-" + regiao.toLowerCase() + ":8080/stock/movimento" +
                            "?produtoId={p}&tipo={t}&quantidade={q}", produtoId, tipo, quantidade)
                    .retrieve().toEntity(String.class);
        } catch (HttpStatusCodeException e) {
            return ResponseEntity.status(e.getStatusCode()).contentType(MediaType.APPLICATION_JSON).body(e.getResponseBodyAsString());
        } catch (Exception e) {
            return erroJson(HttpStatus.SERVICE_UNAVAILABLE, "o no de " + regiao + " nao responde");
        }
    }

    private static ResponseEntity<String> erroJson(HttpStatus estado, String mensagem) {
        return ResponseEntity.status(estado).contentType(MediaType.APPLICATION_JSON)
                .body("{\"erro\":\"" + mensagem + "\"}");
    }

    /** Liga ou desliga os clientes simulados em todos os nos que responderem. */
    @PostMapping("/simulacao")
    public Map<String, Object> simulacao(@RequestParam boolean ativa) {
        operacao.definirSimulacao(ativa);
        List<String> semResposta = new ArrayList<>();
        for (String p : pares) {
            try { http.post().uri(p + "/simulacao?ativa=" + ativa).retrieve().toBodilessEntity(); }
            catch (Exception e) { semResposta.add(nomeDe(p)); }
        }
        return Map.of("vendasAuto", ativa, "semResposta", semResposta);
    }

    /** Totais por estado e ordens mais recentes do fragmento local. */
    @GetMapping("/ordens")
    public Map<String, Object> ordens() {
        Map<String, Object> r = new LinkedHashMap<>();
        Map<String, Object> porEstado = new LinkedHashMap<>();
        try {
            jdbc.queryForList("SELECT estado, count(*) AS total FROM ordem_compra GROUP BY estado")
                    .forEach(l -> porEstado.put((String) l.get("estado"), l.get("total")));
            r.put("ordens", jdbc.queryForList(
                    "SELECT o.id, o.regiao, o.produto_id, p.nome, o.fornecedor_id, o.quantidade, o.estado, " +
                    "to_char(o.criada_em AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS') AS criada " +
                    "FROM ordem_compra o JOIN produto p ON p.id = o.produto_id " +
                    "ORDER BY o.criada_em DESC LIMIT " + LINHAS_ORDENS));
        } catch (Exception e) {
            r.put("ordens", List.of());
        }
        r.put("porEstado", porEstado);
        return r;
    }

    /** Lanca a compra automatica em varias regioes ao mesmo tempo (demonstracao do Ricart-Agrawala). */
    @PostMapping("/compra")
    public List<Object> compra(@RequestParam String produtoId,
                               @RequestParam(defaultValue = "Maputo,Beira") String regioes) throws Exception {
        ExecutorService ex = Executors.newFixedThreadPool(3);
        try {
            List<Future<Object>> fs = new ArrayList<>();
            for (String reg : regioes.split(",")) {
                String url = "http://agente-" + reg.trim().toLowerCase() + ":8080/compras/automatica?produtoId=" + produtoId;
                fs.add(ex.submit(() -> {
                    try { return httpLento.post().uri(url).retrieve().body(Map.class); }
                    catch (Exception e) { return Map.of("no", reg.trim(), "resultado", "SEM RESPOSTA (no em falha)"); }
                }));
            }
            List<Object> res = new ArrayList<>();
            for (Future<Object> f : fs) res.add(f.get());
            return res;
        } finally {
            ex.shutdown();
        }
    }

    @PostMapping("/berkeley")
    public Map<String, Object> sincronizar() {
        return berkeley.sincronizar();
    }

    @PostMapping("/desacertar")
    public Map<String, Object> desacertar() {
        berkeley.desacertar();
        for (String p : pares) {
            try { http.post().uri(p + "/relogio/desacertar").retrieve().toBodilessEntity(); }
            catch (Exception ignorado) { }
        }
        return Map.of("ok", true);
    }
}
