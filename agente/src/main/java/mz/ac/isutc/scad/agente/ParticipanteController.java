package mz.ac.isutc.scad.agente;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import javax.sql.DataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** PARTICIPANTE do 2PC: prepara, confirma ou aborta a sua parte na base de dados local. */
@RestController
@RequestMapping("/tx")
public class ParticipanteController {

    private static final Logger log = LoggerFactory.getLogger(ParticipanteController.class);

    // O PREPARE TRANSACTION nao aceita parametros, por isso o gid e validado (evita SQL injection)
    private static final Pattern GID_VALIDO = Pattern.compile("[A-Za-z0-9_-]{1,100}");
    private static final int ABORTO_PRESUMIDO_S = 300;      // sem decisao ao fim disto, a fase 1 nunca acabou

    private final DataSource dataSource;
    private final JdbcTemplate jdbc;
    private final MotorCompras motor;
    private final RegistoDfs registo;
    private final String regiao;
    private final List<String> pares;
    private final int terminoSegundos;
    private final RestClient http;

    public ParticipanteController(DataSource dataSource, JdbcTemplate jdbc, MotorCompras motor, RegistoDfs registo,
                                  @Value("${scad.regiao}") String regiao, @Value("${scad.pares}") String pares,
                                  @Value("${scad.tx-termino-s}") int terminoSegundos) {
        this.dataSource = dataSource;
        this.jdbc = jdbc;
        this.motor = motor;
        this.registo = registo;
        this.regiao = regiao;
        this.pares = Arrays.stream(pares.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        this.terminoSegundos = terminoSegundos;
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(Duration.ofSeconds(2));
        f.setReadTimeout(Duration.ofSeconds(3));
        this.http = RestClient.builder().requestFactory(f).build();
    }

    /** FASE 1: executa a parte local e vota SIM (PREPARE TRANSACTION) ou NAO (ROLLBACK). */
    @PostMapping("/preparar")
    public Map<String, Object> preparar(@RequestBody Map<String, Object> pedido) {
        String gid = (String) pedido.get("gid");
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("regiao", regiao);
        r.put("gid", gid);
        if (gid == null || !GID_VALIDO.matcher(gid).matches()) {
            r.put("voto", "NAO");
            r.put("motivo", "gid invalido");
            return r;
        }
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);                                   // BEGIN
            try (Statement s = c.createStatement()) {
                s.execute("SET LOCAL lock_timeout = '2s'");           // responde antes do timeout do coordenador
                aplicarParte(c, pedido);
                s.execute("PREPARE TRANSACTION '" + gid + "'");       // voto SIM: fica gravado em disco
                r.put("voto", "SIM");
            } catch (Exception e) {
                c.rollback();                                         // voto NAO: desfaz tudo localmente
                r.put("voto", "NAO");
                r.put("motivo", e.getMessage());
            } finally {
                c.setAutoCommit(true);
            }
        } catch (Exception e) {
            r.put("voto", "NAO");
            r.put("motivo", "sem ligacao a base de dados: " + e.getMessage());
        }
        return r;
    }

    /** O trabalho de cada transacao: uma ordem de compra ou uma alteracao ao catalogo de produtos. */
    private void aplicarParte(Connection c, Map<String, Object> p) throws SQLException {
        String tipo = (String) p.getOrDefault("tipo", "ORDEM");
        switch (tipo) {
            case "ORDEM" -> escrever(c,
                    "INSERT INTO ordem_compra (id, produto_id, fornecedor_id, regiao, quantidade, estado) VALUES (?, ?, ?, ?, ?, 'CONFIRMADA')",
                    p.get("ordemId") + "-" + regiao.toUpperCase(), p.get("produtoId"), p.get("fornecedorId"), regiao, p.get("quantidade"));
            // A ficha do produto (tabela replicada) e gravada nos 3 nos; a linha de stock (fragmento) so existe
            // nos armazens que vendem o produto ("vende" = true na parte deste no)
            case "PRODUTO_CRIAR" -> {
                escrever(c, "INSERT INTO produto (id, nome, unidade, preco) VALUES (?, ?, ?, ?)",
                        p.get("id"), texto(p, "nome"), texto(p, "unidade"), new java.math.BigDecimal(texto(p, "preco")));
                if (vende(p)) {
                    escrever(c, "INSERT INTO stock (produto_id, regiao, quantidade, limite_minimo, fornecedor_id) VALUES (?, ?, ?, ?, ?)",
                            p.get("id"), regiao, p.get("quantidade"), p.get("limiteMinimo"), p.get("fornecedorId"));
                }
            }
            case "PRODUTO_EDITAR" -> {
                if (escrever(c, "UPDATE produto SET nome = ?, unidade = ?, preco = ? WHERE id = ?", texto(p, "nome"), texto(p, "unidade"),
                        new java.math.BigDecimal(texto(p, "preco")), p.get("id")) == 0) {
                    throw new SQLException("produto inexistente em " + regiao);
                }
                boolean tem = temStock(c, p.get("id"));
                if (vende(p) && tem) {
                    exigirSemEncomendaSeMudarFornecedor(c, p);
                    escrever(c, "UPDATE stock SET limite_minimo = ?, fornecedor_id = ? WHERE produto_id = ?",
                            p.get("limiteMinimo"), p.get("fornecedorId"), p.get("id"));
                } else if (vende(p)) {
                    // passa a vender neste armazem: nasce a linha do fragmento
                    escrever(c, "INSERT INTO stock (produto_id, regiao, quantidade, limite_minimo, fornecedor_id) VALUES (?, ?, ?, ?, ?)",
                            p.get("id"), regiao, p.get("quantidade") instanceof Integer q ? q : 0, p.get("limiteMinimo"), p.get("fornecedorId"));
                } else if (tem) {
                    // deixa de vender neste armazem
                    if (temEncomendaAberta(c, p.get("id"))) throw new SQLException("ha uma encomenda a caminho para " + regiao);
                    escrever(c, "DELETE FROM stock WHERE produto_id = ?", p.get("id"));
                }
            }
            case "PRODUTO_APAGAR" -> {
                if (temEncomendaAberta(c, p.get("id"))) throw new SQLException("ha uma encomenda a caminho para " + regiao);
                // A ficha do produto fica: as ordens e as faturas antigas continuam a apontar para ela.
                // Um armazem que nao vendia o produto nao tem linha de stock e vota SIM na mesma.
                escrever(c, "DELETE FROM stock WHERE produto_id = ?", p.get("id"));
            }
            case "CLIENTE_CRIAR" -> escrever(c,
                    "INSERT INTO cliente (id, nome, tipo, email, senha_hash) VALUES (?, ?, ?, ?, ?)",
                    p.get("id"), texto(p, "nome"), texto(p, "tipoCliente"), texto(p, "email"), p.get("senhaHash"));
            default -> throw new SQLException("tipo de transacao desconhecido: " + tipo);
        }
    }

    private static boolean vende(Map<String, Object> p) {
        return Boolean.TRUE.equals(p.get("vende"));
    }

    private static boolean temStock(Connection c, Object produtoId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM stock WHERE produto_id = ? FOR UPDATE")) {
            ps.setObject(1, produtoId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static String texto(Map<String, Object> p, String campo) {
        return String.valueOf(p.get(campo)).trim();
    }

    private static int escrever(Connection c, String sql, Object... valores) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < valores.length; i++) ps.setObject(i + 1, valores[i]);
            return ps.executeUpdate();
        }
    }

    private static boolean temEncomendaAberta(Connection c, Object produtoId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM ordem_compra o WHERE o.produto_id = ? AND " + StockController.ORDEM_ABERTA + " LIMIT 1")) {
            ps.setObject(1, produtoId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static void exigirSemEncomendaSeMudarFornecedor(Connection c, Map<String, Object> p) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT fornecedor_id FROM stock WHERE produto_id = ?")) {
            ps.setObject(1, p.get("id"));
            try (ResultSet rs = ps.executeQuery()) {
                boolean muda = rs.next() && !rs.getString(1).equals(p.get("fornecedorId"));
                if (muda && temEncomendaAberta(c, p.get("id"))) {
                    throw new SQLException("nao pode mudar de fornecedor com uma encomenda a caminho");
                }
            }
        }
    }

    /** FASE 2: decisao COMMIT. */
    @PostMapping("/confirmar/{gid}")
    public Map<String, Object> confirmar(@PathVariable String gid) {
        return finalizar("COMMIT PREPARED", gid);
    }

    /** FASE 2: decisao ABORT. */
    @PostMapping("/abortar/{gid}")
    public Map<String, Object> abortar(@PathVariable String gid) {
        return finalizar("ROLLBACK PREPARED", gid);
    }

    private Map<String, Object> finalizar(String comando, String gid) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("regiao", regiao);
        r.put("gid", gid);
        r.put("comando", comando);
        if (!GID_VALIDO.matcher(gid).matches()) {
            r.put("estado", "ERRO");
            r.put("motivo", "gid invalido");
            return r;
        }
        try {
            jdbc.execute(comando + " '" + gid + "'");
            r.put("estado", "OK");
        } catch (Exception e) {
            r.put("estado", "ERRO");
            r.put("motivo", e.getMessage());
        }
        return r;
    }

    /** Resultado do envio ao fornecedor. So altera ordens CONFIRMADA, para uma ordem recebida nao voltar atras. */
    @PostMapping("/ordem/{ordemId}/estado")
    public ResponseEntity<Map<String, Object>> marcarOrdem(@PathVariable String ordemId, @RequestParam String estado) {
        if (!estado.equals("ENVIADA") && !estado.equals("FALHOU")) {
            return ResponseEntity.badRequest().body(Map.of("erro", "estado invalido"));
        }
        int alteradas = jdbc.update("UPDATE ordem_compra SET estado = ? WHERE id = ? AND estado = 'CONFIRMADA'",
                estado, ordemId + "-" + regiao.toUpperCase());
        return ResponseEntity.ok(Map.of("alteradas", alteradas));
    }

    /** Transacoes preparadas que ainda esperam pela decisao do coordenador. */
    @GetMapping("/pendentes")
    public List<Map<String, Object>> pendentes() {
        return jdbc.queryForList("SELECT gid, prepared FROM pg_prepared_xacts ORDER BY prepared");
    }

    // ---------- Terminacao: uma transacao preparada nunca fica presa se a decisao puder ser conhecida ----------

    /**
     * Decisao de uma transacao, segundo este no. So o coordenador a conhece; se comecou a transacao ha muito
     * tempo e nunca decidiu, a fase 1 foi interrompida e a decisao passa a ser ABORT (aborto presumido).
     */
    @GetMapping("/decisao/{base}")
    public Map<String, Object> decisao(@PathVariable String base) {
        List<String> decisoes = jdbc.queryForList(
                "SELECT detalhe FROM auditoria WHERE operacao = '2PC-DECISAO' AND detalhe LIKE ? ORDER BY id DESC LIMIT 1",
                String.class, base + " %");
        if (!decisoes.isEmpty()) return Map.of("decisao", decisoes.get(0).split(" ")[1]);

        List<String> interrompidas = jdbc.queryForList(
                "SELECT detalhe FROM auditoria WHERE operacao = '2PC-INICIO' AND detalhe LIKE ? " +
                "AND hora < now() - ? * interval '1 second' ORDER BY id DESC LIMIT 1",
                String.class, base + " %", ABORTO_PRESUMIDO_S);
        if (interrompidas.isEmpty()) return Map.of("decisao", "DESCONHECIDA");
        String regioes = interrompidas.get(0).substring(interrompidas.get(0).indexOf('=') + 1);
        motor.auditar("2PC-DECISAO", base + " ABORT " + regioes);
        return Map.of("decisao", "ABORT");
    }

    @Scheduled(initialDelay = 60000, fixedDelay = 30000)
    public void terminarPendentes() {
        List<String> presas;
        try {
            presas = jdbc.queryForList("SELECT gid FROM pg_prepared_xacts WHERE prepared < now() - ? * interval '1 second'",
                    String.class, terminoSegundos);
        } catch (Exception e) {
            return;      // base de dados em baixo: volta a tentar na ronda seguinte
        }
        for (String gid : presas) {
            String base = gid.substring(0, gid.lastIndexOf('-'));
            String decisao = procurarDecisao(base);
            if (decisao.equals("DESCONHECIDA")) continue;       // coordenador em baixo: 2PC bloqueia, nao adivinha
            Map<String, Object> r = finalizar(decisao.equals("COMMIT") ? "COMMIT PREPARED" : "ROLLBACK PREPARED", gid);
            motor.auditar("2PC-TERMINACAO", gid + " " + decisao + " " + r.get("estado"));
            if (!"OK".equals(r.get("estado"))) registo.erro("terminacao de " + gid + " falhou: " + r.get("motivo"));
            log.info("[2PC {}] terminada com {}", gid, decisao);
        }
    }

    private String procurarDecisao(String base) {
        String local = decisao(base).get("decisao").toString();
        if (!local.equals("DESCONHECIDA")) return local;
        for (String par : pares) {
            try {
                Map<?, ?> resposta = http.get().uri(par + "/tx/decisao/{base}", base).retrieve().body(Map.class);
                if (!"DESCONHECIDA".equals(resposta.get("decisao"))) return String.valueOf(resposta.get("decisao"));
            } catch (Exception ignorado) {
                // este no pode ser o coordenador em baixo: pergunta aos restantes
            }
        }
        return "DESCONHECIDA";
    }
}
