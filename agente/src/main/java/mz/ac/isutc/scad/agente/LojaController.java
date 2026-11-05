package mz.ac.isutc.scad.agente;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loja dos clientes (publica, sem login). O cliente escolhe o armazem da sua regiao e faz um pedido;
 * o pedido baixa o stock desse fragmento numa so transacao, e o monitor de compras trata da reposicao.
 * A pagina pode ser aberta em qualquer no: este agente reencaminha para o agente da regiao escolhida.
 */
@RestController
@RequestMapping("/loja")
public class LojaController {

    private static final List<String> REGIOES = List.of("Maputo", "Beira", "Nampula");
    private static final int MAX_ITENS = 20;
    private static final int MAX_POR_ITEM = 1000;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transacao;
    private final MotorCompras motor;
    private final Relogio relogio;
    private final String regiao;
    private final RestClient http;

    public LojaController(JdbcTemplate jdbc, TransactionTemplate transacao, MotorCompras motor, Relogio relogio,
                          @Value("${scad.regiao}") String regiao) {
        this.jdbc = jdbc;
        this.relogio = relogio;
        this.transacao = transacao;
        this.motor = motor;
        this.regiao = regiao;
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(Duration.ofSeconds(2));
        f.setReadTimeout(Duration.ofSeconds(10));
        this.http = RestClient.builder().requestFactory(f).build();
    }

    private static String urlDe(String r) {
        return "http://agente-" + r.toLowerCase() + ":8080";
    }

    // ---------- Rotas usadas pela pagina: reencaminham para o armazem escolhido ----------

    @GetMapping("/catalogo")
    public ResponseEntity<String> catalogo(@RequestParam String regiao) {
        if (!REGIOES.contains(regiao)) return erro(HttpStatus.BAD_REQUEST, "armazem invalido");
        try {
            return http.get().uri(urlDe(regiao) + "/loja/local/catalogo").retrieve().toEntity(String.class);
        } catch (Exception e) {
            return erro(HttpStatus.SERVICE_UNAVAILABLE, "A loja de " + regiao + " esta indisponivel neste momento.");
        }
    }

    /** So clientes com sessao iniciada: o pedido fica associado a conta, nunca a um nome escrito a mao. */
    @PostMapping("/pedido")
    public ResponseEntity<String> pedido(@RequestParam String regiao, @RequestBody Map<String, Object> pedido,
                                         jakarta.servlet.http.HttpServletRequest req) {
        Map<String, Object> cliente = ContaController.clienteDaSessao(req);
        if (cliente == null) return erro(HttpStatus.UNAUTHORIZED, "Entre na sua conta para fazer pedidos.");
        if (!REGIOES.contains(regiao)) return erro(HttpStatus.BAD_REQUEST, "armazem invalido");
        Map<String, Object> corpo = new LinkedHashMap<>();
        corpo.put("itens", pedido.get("itens"));
        corpo.put("clienteId", cliente.get("id"));
        corpo.put("cliente", cliente.get("nome") + " (" + cliente.get("tipo") + ")");
        try {
            return http.post().uri(urlDe(regiao) + "/loja/local/pedido").contentType(MediaType.APPLICATION_JSON)
                    .body(corpo).retrieve().toEntity(String.class);
        } catch (HttpStatusCodeException e) {
            return ResponseEntity.status(e.getStatusCode()).contentType(MediaType.APPLICATION_JSON).body(e.getResponseBodyAsString());
        } catch (Exception e) {
            return erro(HttpStatus.SERVICE_UNAVAILABLE, "A loja de " + regiao + " esta indisponivel neste momento.");
        }
    }

    /** Historico do cliente com sessao: junta os pedidos guardados nos 3 armazens. */
    @GetMapping("/meus-pedidos")
    public ResponseEntity<Map<String, Object>> meusPedidos(jakarta.servlet.http.HttpServletRequest req) {
        Map<String, Object> cliente = ContaController.clienteDaSessao(req);
        if (cliente == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("erro", "sem sessao"));
        return ResponseEntity.ok(pedidosDe((String) cliente.get("id")));
    }

    /** Pedidos de um cliente (ou de todos) nos 3 armazens; os armazens em baixo sao indicados. */
    Map<String, Object> pedidosDe(String clienteId) {
        List<Object> linhas = new ArrayList<>();
        List<String> semResposta = new ArrayList<>();
        for (String r : REGIOES) {
            try {
                linhas.addAll(http.get().uri(urlDe(r) + "/loja/local/pedidos?cliente={c}", clienteId == null ? "" : clienteId)
                        .retrieve().body(List.class));
            } catch (Exception e) {
                semResposta.add(r);
            }
        }
        linhas.sort((a, b) -> String.valueOf(((Map<?, ?>) b).get("criado")).compareTo(String.valueOf(((Map<?, ?>) a).get("criado"))));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("linhas", linhas);
        out.put("semResposta", semResposta);
        return out;
    }

    // ---------- Rotas do proprio armazem ----------

    @GetMapping("/local/pedidos")
    public List<Map<String, Object>> pedidosLocais(@RequestParam(defaultValue = "") String cliente) {
        return jdbc.queryForList(
                "SELECT pc.pedido, pc.regiao, pc.cliente_id, c.nome AS cliente, c.tipo, pc.produto_id, p.nome AS produto, p.unidade, " +
                "pc.quantidade, to_char(pc.criado_em AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS') AS criado " +
                "FROM pedido_cliente pc JOIN cliente c ON c.id = pc.cliente_id JOIN produto p ON p.id = pc.produto_id " +
                "WHERE (? = '' OR pc.cliente_id = ?) ORDER BY pc.id DESC LIMIT 300", cliente, cliente);
    }

    @GetMapping("/local/resumo-clientes")
    public List<Map<String, Object>> resumoLocal() {
        return jdbc.queryForList(
                "SELECT cliente_id, count(DISTINCT pedido) AS pedidos, sum(quantidade) AS unidades, " +
                "to_char(max(criado_em) AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS') AS ultimo " +
                "FROM pedido_cliente GROUP BY cliente_id");
    }

    @GetMapping("/local/catalogo")
    public List<Map<String, Object>> catalogoLocal() {
        return jdbc.queryForList(
                "SELECT s.produto_id AS id, p.nome, p.unidade, s.quantidade AS disponivel " +
                "FROM stock s JOIN produto p ON p.id = s.produto_id ORDER BY p.nome");
    }

    /** Todos os itens do pedido saem do stock ou nenhum: um item sem stock cancela o pedido inteiro. */
    @PostMapping("/local/pedido")
    public ResponseEntity<String> pedidoLocal(@RequestBody Map<String, Object> pedido) {
        String cliente = pedido.get("cliente") instanceof String s ? s.trim().replaceAll("[|\\r\\n]", " ") : "";
        String clienteId = pedido.get("clienteId") instanceof String c ? c : "";
        if (cliente.isEmpty() || cliente.length() > 90 || clienteId.isEmpty()) return erro(HttpStatus.BAD_REQUEST, "Entre na sua conta para fazer pedidos.");
        Map<String, Integer> itens = new LinkedHashMap<>();
        if (!(pedido.get("itens") instanceof List<?> lista) || lista.isEmpty() || lista.size() > MAX_ITENS) {
            return erro(HttpStatus.BAD_REQUEST, "O pedido tem de ter entre 1 e " + MAX_ITENS + " produtos.");
        }
        for (Object o : lista) {
            if (!(o instanceof Map<?, ?> item) || !(item.get("produtoId") instanceof String id)
                    || !(item.get("quantidade") instanceof Integer q) || q < 1 || q > MAX_POR_ITEM) {
                return erro(HttpStatus.BAD_REQUEST, "Cada produto tem de ter uma quantidade entre 1 e " + MAX_POR_ITEM + ".");
            }
            itens.merge(id, q, Integer::sum);
        }

        String numero = "PED-" + System.currentTimeMillis() + "-" + regiao.substring(0, 3).toUpperCase();
        List<Map<String, Object>> linhas = new ArrayList<>();
        List<String> aRepor = new ArrayList<>();
        try {
            transacao.executeWithoutResult(estado -> {
                for (Map.Entry<String, Integer> item : itens.entrySet()) {
                    List<Map<String, Object>> r = jdbc.queryForList(
                            "UPDATE stock s SET quantidade = s.quantidade - ? FROM produto p " +
                            "WHERE s.produto_id = ? AND p.id = s.produto_id AND s.quantidade >= ? " +
                            "RETURNING p.nome, s.quantidade, s.limite_minimo", item.getValue(), item.getKey(), item.getValue());
                    if (r.isEmpty()) throw new PedidoRecusado(motivo(item.getKey(), item.getValue()));
                    int fica = ((Number) r.get(0).get("quantidade")).intValue();
                    int minimo = ((Number) r.get(0).get("limite_minimo")).intValue();
                    jdbc.update("INSERT INTO auditoria (no_origem, hora, operacao, detalhe) VALUES (?, ?, 'VENDA', ?)",
                            "agente-" + regiao.toLowerCase(), java.sql.Timestamp.from(relogio.agora()),
                            item.getKey() + " -" + item.getValue() + " -> " + fica + " (minimo " + minimo + ") | pedido " + numero + " de " + cliente);
                    jdbc.update("INSERT INTO pedido_cliente (pedido, cliente_id, regiao, produto_id, quantidade, criado_em) VALUES (?, ?, ?, ?, ?, ?)",
                            numero, clienteId, regiao, item.getKey(), item.getValue(), java.sql.Timestamp.from(relogio.agora()));
                    if (fica <= minimo) aRepor.add(item.getKey());
                    Map<String, Object> l = new LinkedHashMap<>();
                    l.put("produtoId", item.getKey());
                    l.put("nome", r.get(0).get("nome"));
                    l.put("quantidade", item.getValue());
                    linhas.add(l);
                }
            });
        } catch (PedidoRecusado e) {
            return erro(HttpStatus.CONFLICT, e.getMessage());
        } catch (Exception e) {
            return erro(HttpStatus.SERVICE_UNAVAILABLE, "A loja de " + regiao + " nao consegue registar pedidos agora. Tente mais tarde.");
        }
        aRepor.forEach(motor::agendar);          // atingiu o minimo: a compra ao fornecedor arranca ja

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("pedido", numero);
        r.put("regiao", regiao);
        r.put("cliente", cliente);
        r.put("itens", linhas);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(json(r));
    }

    private String motivo(String produtoId, int pedida) {
        List<Map<String, Object>> r = jdbc.queryForList(
                "SELECT p.nome, s.quantidade FROM stock s JOIN produto p ON p.id = s.produto_id WHERE s.produto_id = ?", produtoId);
        if (r.isEmpty()) return "Um dos produtos deixou de estar a venda.";
        return "So ha " + r.get(0).get("quantidade") + " de " + r.get(0).get("nome") + " em " + regiao
                + " (pediu " + pedida + "). O pedido nao foi registado.";
    }

    private static String json(Object o) {
        try {
            return new tools.jackson.databind.ObjectMapper().writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static ResponseEntity<String> erro(HttpStatus estado, String mensagem) {
        return ResponseEntity.status(estado).contentType(MediaType.APPLICATION_JSON).body(json(Map.of("erro", mensagem)));
    }

    /** Desfaz a transacao do pedido inteiro. */
    private static class PedidoRecusado extends RuntimeException {
        PedidoRecusado(String mensagem) {
            super(mensagem);
        }
    }
}
