package mz.ac.isutc.scad.agente;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Fragmento local de STOCK e reconstrucao da tabela global (uniao dos fragmentos). */
@RestController
public class StockController {

    /**
     * Ordem que ainda vai trazer mercadoria. Uma CONFIRMADA so conta durante 2 minutos: depois disso
     * o envio ao fornecedor ficou a meio (coordenador caiu) e o lote pode voltar a ser comprado.
     */
    static final String ORDEM_ABERTA =
            "(o.estado = 'ENVIADA' OR (o.estado = 'CONFIRMADA' AND o.criada_em > now() - interval '2 minutes'))";

    private final JdbcTemplate jdbc;
    private final String regiao;
    private final List<String> pares;
    private final RestClient http;

    public StockController(JdbcTemplate jdbc,
                           @Value("${scad.regiao}") String regiao,
                           @Value("${scad.pares}") String pares) {
        this.jdbc = jdbc;
        this.regiao = regiao;
        this.pares = Arrays.stream(pares.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(Duration.ofSeconds(2));
        f.setReadTimeout(Duration.ofSeconds(2));
        this.http = RestClient.builder().requestFactory(f).build();
    }

    /** Fragmento local: apenas as linhas desta regiao. */
    @GetMapping("/stock")
    public List<Map<String, Object>> stockLocal() {
        return jdbc.queryForList(
            "SELECT s.produto_id, p.nome, p.unidade, p.preco, s.regiao, s.quantidade, s.limite_minimo, " +
            "       s.fornecedor_id, s.quantidade <= s.limite_minimo AS abaixo_minimo, " +
            "       (SELECT COALESCE(SUM(o.quantidade), 0) FROM ordem_compra o " +
            "        WHERE o.produto_id = s.produto_id AND " + ORDEM_ABERTA + ") AS em_encomenda " +
            "FROM stock s JOIN produto p ON p.id = s.produto_id " +
            "ORDER BY s.produto_id");
    }

    /** Reconstrucao: STOCK = STOCK_MAPUTO U STOCK_BEIRA U STOCK_NAMPULA */
    @GetMapping("/stock/global")
    public Map<String, Object> stockGlobal() {
        List<Object> linhas = new ArrayList<>();
        List<String> indisponiveis = new ArrayList<>();
        try {
            linhas.addAll(stockLocal());
        } catch (Exception e) {
            indisponiveis.add("http://agente-" + regiao.toLowerCase() + ":8080");   // mestre e replica em baixo
        }
        for (String par : pares) {
            try {
                List<?> remotas = http.get().uri(par + "/stock").retrieve().body(List.class);
                if (remotas != null) linhas.addAll(remotas);
            } catch (Exception e) {
                indisponiveis.add(par);          // fragmento inacessivel: resultado parcial
            }
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("consultadoPor", regiao);
        r.put("totalLinhas", linhas.size());
        r.put("fragmentosIndisponiveis", indisponiveis);
        r.put("linhas", linhas);
        return r;
    }
}
