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
    private final RegistoDfs registo;
    private final String regiao;

    public CompraAutomaticaController(JdbcTemplate jdbc, RicartAgrawala ra, StockController stock,
                                      CoordenadorController coordenador, FornecedorCliente fornecedores,
                                      DfsController dfs, Relogio relogio, RegistoDfs registo,
                                      @Value("${scad.regiao}") String regiao) {
        this.registo = registo;
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
        if (q > min) { passos.add("1. stock OK (" + q + " acima do minimo " + min + "): nada a comprar"); return r; }
        passos.add("1. stock atingiu o minimo: " + q + " <= " + min + " (fornecedor " + fornecedorId + ")");

        // 2. Exclusao mutua distribuida sobre o lote
        String recurso = fornecedorId + ":" + produtoId;
        long t0 = System.currentTimeMillis();
        Map<String, Object> ra1 = ra.entrar(recurso);
        passos.add("2. Ricart-Agrawala: entrou na seccao critica de " + recurso + " apos "
                + (System.currentTimeMillis() - t0) + " ms (ts Lamport " + ra1.get("lamportTs") + ")");
        try {
            // 3. Verificacao dentro da seccao critica
            // O stock e relido: enquanto este no esperava, outro pode ter comprado ou a mercadoria pode ter chegado
            int qAgora = jdbc.queryForObject("SELECT quantidade FROM stock WHERE produto_id = ?", Integer.class, produtoId);
            List<Map<String, Object>> abertas = jdbc.queryForList(
                    "SELECT o.id, o.quantidade FROM ordem_compra o WHERE o.produto_id = ? AND o.fornecedor_id = ? " +
                    "AND " + StockController.ORDEM_ABERTA + " ORDER BY o.criada_em DESC",
                    produtoId, fornecedorId);
            int aCaminho = abertas.stream().mapToInt(o -> ((Number) o.get("quantidade")).intValue()).sum();
            if (qAgora + aCaminho > min) {
                passos.add(abertas.isEmpty()
                        ? "3. stock reposto entretanto (" + qAgora + " acima de " + min + "): nada a comprar"
                        : "3. lote ja comprado por outro no (" + abertas.get(0).get("id") + "): NAO duplica");
                r.put("resultado", "SEM COMPRA - lote ja encomendado");
                return r;
            }
            passos.add("3. stock " + qAgora + " + " + aCaminho + " a caminho <= " + min + ": este no faz a compra");

            // 4. Necessidades de todas as regioes (tabela global reconstruida)
            Map<String, Object> quantidades = new LinkedHashMap<>();
            Object linhas = stock.stockGlobal().get("linhas");
            for (Object o : (List<?>) linhas) {
                Map<?, ?> m = (Map<?, ?>) o;
                if (produtoId.equals(m.get("produto_id")) && fornecedorId.equals(m.get("fornecedor_id"))
                        && Boolean.TRUE.equals(m.get("abaixo_minimo"))) {
                    int qa = ((Number) m.get("quantidade")).intValue();
                    int ma = ((Number) m.get("limite_minimo")).intValue();
                    int enc = ((Number) m.get("em_encomenda")).intValue();
                    if (qa + enc <= ma) quantidades.put((String) m.get("regiao"), 2 * ma - qa - enc);   // repor ate 2x o minimo
                }
            }
            passos.add("4. stock global: regioes a repor = " + quantidades);
            if (quantidades.isEmpty()) {
                r.put("resultado", "SEM COMPRA - nenhuma regiao precisa de repor");
                return r;
            }

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
                coordenador.marcarOrdem(ordemId, quantidades.keySet(), "FALHOU");   // nao vira mercadoria nem fatura
                registarErro("ordem " + ordemId + " nao entregue ao fornecedor " + fornecedorId + ": " + envio.get("estado"));
                r.put("ordemId", ordemId);
                r.put("resultado", "COMPRA FALHOU - fornecedor sem resposta ou rejeitou");
                return r;
            }
            coordenador.marcarOrdem(ordemId, quantidades.keySet(), "ENVIADA");     // a mercadoria vem a caminho

            // 7. Fatura no HDFS
            String caminho = dfs.guardarFatura(ordemId, fornecedorId, produtoId, total);
            passos.add("7. fatura enviada para o HDFS: " + caminho);
            r.put("fatura", caminho);
            r.put("ordemId", ordemId);
            r.put("resultado", "COMPRA CONCLUIDA");
            return r;
        } finally {
            ra.sair(recurso);
            passos.add("8. saiu da seccao critica (hora Berkeley " + relogio.agora() + ")");
        }
    }

    private void registarErro(String texto) {
        registo.erro(texto);
    }
}
