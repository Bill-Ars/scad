package mz.ac.isutc.scad.agente;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private final RestClient http;
    private final RestClient httpLento;

    public PainelController(JdbcTemplate jdbc, @Value("${scad.regiao}") String regiao,
                            @Value("${scad.pares}") String pares, EstadoController estado,
                            StockController stock, BerkeleyController berkeley, HdfsCliente hdfs) {
        this.jdbc = jdbc;
        this.regiao = regiao;
        this.pares = Arrays.stream(pares.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        this.estado = estado;
        this.stock = stock;
        this.berkeley = berkeley;
        this.hdfs = hdfs;
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
        eu.put("online", true);
        nos.add(eu);
        for (String p : pares) {
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("regiao", nomeDe(p));
            try {
                n.putAll(http.get().uri(p + "/estado").retrieve().body(Map.class));
                n.putAll(http.get().uri(p + "/relogio").retrieve().body(Map.class));
                n.put("online", true);
            } catch (Exception e) {
                n.put("online", false);
            }
            nos.add(n);
        }
        r.put("nos", nos);

        // Stock global (reconstrucao dos fragmentos)
        r.put("stock", stock.stockGlobal());

        // Ordens de compra de todas as regioes
        List<Object> ordens = new ArrayList<>(ordens());
        for (String p : pares) {
            try { ordens.addAll(http.get().uri(p + "/painel/ordens").retrieve().body(List.class)); }
            catch (Exception ignorado) { }
        }
        r.put("ordens", ordens);

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
        try { dfs.put("faturas", hdfs.listar("/scad/faturas")); }
        catch (Exception e) { dfs.put("faturas", List.of()); }
        r.put("hdfs", dfs);

        // Fornecedores
        Map<String, Object> forn = new LinkedHashMap<>();
        try {
            forn.put("restOrdens", http.get().uri("http://fornecedor-rest:8080/api/ordens").retrieve().body(List.class));
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

        // Auditoria local (hora Berkeley)
        try {
            r.put("auditoria", jdbc.queryForList(
                    "SELECT to_char(hora AT TIME ZONE 'UTC', 'HH24:MI:SS.MS') AS hora, no_origem, operacao, detalhe " +
                    "FROM auditoria ORDER BY id DESC LIMIT 12"));
        } catch (Exception e) {
            r.put("auditoria", List.of());
        }
        return r;
    }

    /** Ordens recentes do fragmento local. */
    @GetMapping("/ordens")
    public List<Map<String, Object>> ordens() {
        try {
            return jdbc.queryForList(
                    "SELECT id, regiao, produto_id, fornecedor_id, quantidade, estado, " +
                    "to_char(criada_em AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS') AS criada " +
                    "FROM ordem_compra ORDER BY criada_em DESC LIMIT 8");
        } catch (Exception e) {
            return List.of();
        }
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
