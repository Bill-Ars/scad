package mz.ac.isutc.scad.agente;

import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/** Operacao do armazem: vendas e entradas de stock no fragmento local, feitas por pessoas ou por clientes simulados. */
@RestController
public class OperacaoController {

    private static final int QUANTIDADE_MAXIMA = 100_000;
    private static final int VENDA_AUTO_MIN = 5;
    private static final int VENDA_AUTO_MAX = 20;

    private static final Duration DURACAO_VENDAS_AUTO = Duration.ofMinutes(10);

    private final JdbcTemplate jdbc;
    private final MotorCompras motor;
    private final RegistoDfs registo;
    private final String regiao;
    private volatile boolean vendasAuto = false;
    private volatile Instant vendasAutoAte = Instant.EPOCH;

    public OperacaoController(JdbcTemplate jdbc, MotorCompras motor, RegistoDfs registo, @Value("${scad.regiao}") String regiao) {
        this.jdbc = jdbc;
        this.motor = motor;
        this.registo = registo;
        this.regiao = regiao;
    }

    @PostMapping("/stock/movimento")
    public ResponseEntity<Map<String, Object>> movimento(@RequestParam String produtoId, @RequestParam String tipo,
                                                         @RequestParam int quantidade) {
        if (!tipo.equals("SAIDA") && !tipo.equals("ENTRADA")) return erro(HttpStatus.BAD_REQUEST, "tipo invalido");
        if (quantidade < 1 || quantidade > QUANTIDADE_MAXIMA) {
            return erro(HttpStatus.BAD_REQUEST, "a quantidade tem de estar entre 1 e " + QUANTIDADE_MAXIMA);
        }
        try {
            Map<String, Object> r = aplicar(produtoId, tipo, quantidade, tipo.equals("SAIDA") ? "VENDA" : "ENTRADA");
            if (r != null) return ResponseEntity.ok(r);
            List<Integer> saldo = jdbc.queryForList("SELECT quantidade FROM stock WHERE produto_id = ?", Integer.class, produtoId);
            if (saldo.isEmpty()) return erro(HttpStatus.NOT_FOUND, "produto inexistente em " + regiao);
            return erro(HttpStatus.CONFLICT, "stock insuficiente em " + regiao + ": existem " + saldo.get(0) + " unidades");
        } catch (DataAccessException e) {
            registo.erro("movimento de " + produtoId + " recusado: base de dados sem escrita");
            return erro(HttpStatus.SERVICE_UNAVAILABLE, "O armazem de " + regiao + " esta so com a copia de seguranca e nao pode registar movimentos agora");
        }
    }

    /** Devolve null se o produto nao existir ou o stock nao chegar. */
    private Map<String, Object> aplicar(String produtoId, String tipo, int quantidade, String operacao) {
        boolean saida = tipo.equals("SAIDA");
        // A condicao no proprio UPDATE evita vender stock que outra venda simultanea ja levou
        List<Map<String, Object>> linhas = jdbc.queryForList(saida
                ? "UPDATE stock SET quantidade = quantidade - ? WHERE produto_id = ? AND quantidade >= ? RETURNING quantidade, limite_minimo"
                : "UPDATE stock SET quantidade = quantidade + ? WHERE produto_id = ? AND ? > 0 RETURNING quantidade, limite_minimo",
                quantidade, produtoId, quantidade);
        if (linhas.isEmpty()) return null;

        int atual = ((Number) linhas.get(0).get("quantidade")).intValue();
        int minimo = ((Number) linhas.get(0).get("limite_minimo")).intValue();
        motor.auditar(operacao, produtoId + (saida ? " -" : " +") + quantidade + " -> " + atual + " (minimo " + minimo + ")");
        boolean abaixo = atual <= minimo;
        if (saida && abaixo) motor.agendar(produtoId);

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("regiao", regiao);
        r.put("produtoId", produtoId);
        r.put("quantidade", atual);
        r.put("limiteMinimo", minimo);
        r.put("abaixoMinimo", abaixo);
        r.put("compraAgendada", saida && abaixo);
        return r;
    }

    private static ResponseEntity<Map<String, Object>> erro(HttpStatus estado, String mensagem) {
        return ResponseEntity.status(estado).body(Map.of("erro", mensagem));
    }

    // ---------- Clientes simulados: vendem sozinhos enquanto estiverem ligados ----------

    @GetMapping("/simulacao")
    public Map<String, Object> simulacao() {
        return Map.of("vendasAuto", vendasAuto);
    }

    @PostMapping("/simulacao")
    public Map<String, Object> definirSimulacao(@RequestParam boolean ativa) {
        vendasAuto = ativa;
        vendasAutoAte = Instant.now().plus(DURACAO_VENDAS_AUTO);
        return simulacao();
    }

    @Scheduled(fixedDelay = 5000)
    public void vendaAutomatica() {
        if (!vendasAuto) return;
        // Desliga-se sozinho: esquecido ligado, enchia o sistema de ordens e faturas
        if (Instant.now().isAfter(vendasAutoAte)) {
            vendasAuto = false;
            return;
        }
        try {
            List<Map<String, Object>> linhas = jdbc.queryForList("SELECT produto_id, quantidade FROM stock WHERE quantidade > 0");
            if (linhas.isEmpty()) return;
            Map<String, Object> linha = linhas.get(ThreadLocalRandom.current().nextInt(linhas.size()));
            int disponivel = ((Number) linha.get("quantidade")).intValue();
            int pedido = ThreadLocalRandom.current().nextInt(VENDA_AUTO_MIN, VENDA_AUTO_MAX + 1);
            aplicar((String) linha.get("produto_id"), "SAIDA", Math.min(pedido, disponivel), "VENDA-AUTO");
        } catch (DataAccessException ignorado) {
            // mestre em falha: as lojas desta regiao nao conseguem registar vendas ate ser reposto
        }
    }
}
