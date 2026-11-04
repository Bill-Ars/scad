package mz.ac.isutc.scad.agente;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * FLUXO COMPLETO de uma compra automatica (integra as 5 componentes):
 *  1. deteta stock abaixo do minimo no fragmento local                       (SBDD - fragmentacao)
 *  2. pede a seccao critica do lote fornecedor:produto                         (SOD  - Ricart-Agrawala)
 *  3. dentro da SC verifica se outro no ja comprou o lote (evita duplicados)
 *  4. reune as necessidades de TODAS as regioes (reconstrucao do stock global)  (SBDD)
 *  5. regista a ordem em todos os armazens envolvidos de forma atomica         (SBDD - 2PC)
 *  6. envia a ordem ao fornecedor pela rota REST/JSON ou SOAP/XML               (SWD)
 *  7. guarda a fatura PDF e, em caso de erro, o log no HDFS                     (DFS)
 *  Todos os registos de auditoria usam a hora sincronizada por Berkeley        (SOD)
 *  Os alertas de stock para a auditoria central seguem por CORBA (robos de stock).
 */
@RestController
public class CompraAutomaticaController {

    private final JdbcTemplate jdbc;
    private final RicartAgrawala ra;
    private final StockController stock;
    private final CoordenadorController coordenador;
    private final FornecedorCliente fornecedores;
    private final DfsController dfs;
    private final Relogio relogio;
    private final String regiao;

    public CompraAutomaticaController(JdbcTemplate jdbc, RicartAgrawala ra, StockController stock,
                                      CoordenadorController coordenador, FornecedorCliente fornecedores,
                                      DfsController dfs, Relogio relogio, @Value("${scad.regiao}") String regiao) {
        this.jdbc = jdbc;
        this.ra = ra;
        this.stock = stock;
        this.coordenador = coordenador;
        this.fornecedores = fornecedores;
        this.dfs = dfs;
        this.relogio = relogio;
        this.regiao = regiao;
    }

    @PostMapping("/compras/automatica")
    public Map<String, Object> executar(@RequestParam String produtoId) throws InterruptedException {
        Map<String, Object> r = new LinkedHashMap<>();
        List<String> passos = new ArrayList<>();
        r.put("no", regiao);
        r.put("produtoId", produtoId);
        r.put("passos", passos);

        // 1. Monitorizacao do fragmento local
        List<Map<String, Object>> linha = jdbc.queryForList(
                "SELECT quantidade, limite_minimo, fornecedor_id FROM stock WHERE produto_id = ?", produtoId);
        if (linha.isEmpty()) { passos.add("1. produto inexistente neste armazem"); return r; }
        int q = ((Number) linha.get(0).get("quantidade")).intValue();
        int min = ((Number) linha.get(0).get("limite_minimo")).intValue();
        String fornecedorId = (String) linha.get(0).get("fornecedor_id");
        if (q >= min) { passos.add("1. stock OK (" + q + " >= " + min + "): nada a comprar"); return r; }
        passos.add("1. stock abaixo do minimo: " + q + " < " + min + " (fornecedor " + fornecedorId + ")");

        // 2. Exclusao mutua distribuida sobre o lote
        String recurso = fornecedorId + ":" + produtoId;
        long t0 = System.currentTimeMillis();
        Map<String, Object> ra1 = ra.entrar(recurso);
        passos.add("2. Ricart-Agrawala: entrou na seccao critica de " + recurso + " apos "
                + (System.currentTimeMillis() - t0) + " ms (ts Lamport " + ra1.get("lamportTs") + ")");
        try {
            // 3. Verificacao dentro da seccao critica
            List<String> recente = jdbc.queryForList(
                    "SELECT id FROM ordem_compra WHERE produto_id = ? AND fornecedor_id = ? " +
                    "AND criada_em > now() - interval '2 minutes' ORDER BY criada_em DESC LIMIT 1",
                    String.class, produtoId, fornecedorId);
            if (!recente.isEmpty()) {
                passos.add("3. lote ja comprado por outro no (" + recente.get(0) + "): NAO duplica");
                r.put("resultado", "SEM COMPRA - lote ja encomendado");
                return r;
            }
            passos.add("3. nenhuma ordem recente para este lote: este no faz a compra");

            // 4. Necessidades de todas as regioes (tabela global reconstruida)
            Map<String, Object> quantidades = new LinkedHashMap<>();
            Object linhas = stock.stockGlobal().get("linhas");
            for (Object o : (List<?>) linhas) {
                Map<?, ?> m = (Map<?, ?>) o;
                if (produtoId.equals(m.get("produto_id")) && fornecedorId.equals(m.get("fornecedor_id"))
                        && Boolean.TRUE.equals(m.get("abaixo_minimo"))) {
                    int qa = ((Number) m.get("quantidade")).intValue();
                    int ma = ((Number) m.get("limite_minimo")).intValue();
                    quantidades.put((String) m.get("regiao"), 2 * ma - qa);   // repor ate 2x o minimo
                }
            }
            passos.add("4. stock global: regioes a repor = " + quantidades);

            // 5. 2PC entre os armazens envolvidos
            Map<String, Object> pedido = new LinkedHashMap<>();
            pedido.put("produtoId", produtoId);
            pedido.put("fornecedorId", fornecedorId);
            pedido.put("quantidades", quantidades);
            Map<String, Object> tpc = coordenador.compraMulti(pedido);
            String ordemId = (String) tpc.get("ordemId");
            passos.add("5. 2PC " + tpc.get("transacao") + ": votos " + tpc.get("fase1_votos") + " -> " + tpc.get("decisao"));
            if (!"COMMIT".equals(tpc.get("decisao"))) {
                registarErro("2PC abortado para " + ordemId + ": " + tpc.get("fase1_votos"));
                r.put("resultado", "ABORTADA no 2PC");
                return r;
            }

            // 6. Envio ao fornecedor (REST ou SOAP)
            int total = quantidades.values().stream().mapToInt(v -> ((Number) v).intValue()).sum();
            Map<String, Object> envio = fornecedores.enviar(ordemId, fornecedorId, produtoId, total);
            passos.add("6. fornecedor via " + envio.get("rota") + ": " + envio.get("estado") + " " + envio.get("tentativas"));
            if (!"ENTREGUE".equals(envio.get("estado"))) {
                registarErro("ordem " + ordemId + " nao entregue ao fornecedor " + fornecedorId + ": " + envio.get("estado"));
            }

            // 7. Fatura no HDFS
            try {
                String caminho = dfs.guardarFatura(ordemId, fornecedorId, produtoId, total);
                passos.add("7. fatura guardada no HDFS: " + caminho);
                r.put("fatura", caminho);
            } catch (Exception e) {
                passos.add("7. HDFS indisponivel: fatura nao guardada (" + e.getClass().getSimpleName() + ")");
            }
            r.put("ordemId", ordemId);
            r.put("resultado", "COMPRA CONCLUIDA");
            return r;
        } finally {
            ra.sair(recurso);
            passos.add("8. saiu da seccao critica (hora Berkeley " + relogio.agora() + ")");
        }
    }

    private void registarErro(String texto) {
        try {
            dfs.log(Map.of("texto", texto));
        } catch (Exception ignorado) {
            // HDFS indisponivel: o erro fica apenas na auditoria local
        }
    }
}
