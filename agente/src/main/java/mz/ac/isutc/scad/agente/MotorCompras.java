package mz.ac.isutc.scad.agente;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Torna o ciclo autonomo: uma venda que deixa o stock abaixo do minimo agenda a compra automatica,
 * e as ordens aceites pelo fornecedor entram no stock quando a mercadoria chega.
 * As compras falhadas (fornecedor ou no em baixo) sao repetidas ate terem sucesso.
 */
@Component
public class MotorCompras {

    private static final Logger log = LoggerFactory.getLogger(MotorCompras.class);
    private static final int ESPERA_NOVA_TENTATIVA_S = 30;

    private final CompraAutomaticaController compras;
    private final JdbcTemplate jdbc;
    private final Relogio relogio;
    private final RegistoDfs registo;
    private final String regiao;
    private final int entregaSegundos;
    private final Map<String, Instant> agendadas = new ConcurrentHashMap<>();   // produto -> nao antes de

    public MotorCompras(CompraAutomaticaController compras, JdbcTemplate jdbc, Relogio relogio, RegistoDfs registo,
                        @Value("${scad.regiao}") String regiao, @Value("${scad.entrega-s}") int entregaSegundos) {
        this.compras = compras;
        this.jdbc = jdbc;
        this.relogio = relogio;
        this.registo = registo;
        this.regiao = regiao;
        this.entregaSegundos = entregaSegundos;
    }

    public void agendar(String produtoId) {
        agendadas.putIfAbsent(produtoId, Instant.now());
    }

    /** Monitorizacao continua: apanha qualquer stock no limite, venha de uma venda, de uma correcao ou do arranque. */
    @Scheduled(initialDelay = 15000, fixedDelay = 10000)
    public void monitorizar() {
        try {
            jdbc.queryForList(
                    "SELECT s.produto_id FROM stock s WHERE s.quantidade + " +
                    "(SELECT COALESCE(SUM(o.quantidade), 0) FROM ordem_compra o " +
                    " WHERE o.produto_id = s.produto_id AND " + StockController.ORDEM_ABERTA + ") <= s.limite_minimo",
                    String.class).forEach(this::agendar);
        } catch (Exception e) {
            log.debug("[{}] monitorizacao sem base de dados: {}", regiao, e.getMessage());
        }
    }

    @Scheduled(fixedDelay = 3000)
    public void processarCompras() {
        for (Map.Entry<String, Instant> e : agendadas.entrySet()) {
            if (e.getValue().isAfter(Instant.now())) continue;
            String produtoId = e.getKey();
            try {
                Map<String, Object> r = compras.executar(produtoId);
                String resultado = (String) r.get("resultado");
                // Sem resultado = stock ja acima do minimo; SEM COMPRA = lote ja encomendado: nada a registar
                if (resultado == null || resultado.startsWith("SEM COMPRA")) {
                    agendadas.remove(produtoId);
                    continue;
                }
                auditar("COMPRA-AUTO", produtoId + ": " + resultado + " | "
                        + String.join(" | ", (List<String>) r.get("passos")));
                if ("COMPRA CONCLUIDA".equals(resultado)) agendadas.remove(produtoId);
                else repetirMaisTarde(produtoId);
            } catch (Exception ex) {
                log.warn("[{}] compra automatica de {} falhou: {}", regiao, produtoId, ex.getMessage());
                registo.erro("compra automatica de " + produtoId + " falhou: " + ex.getMessage());
                repetirMaisTarde(produtoId);
            }
        }
    }

    private void repetirMaisTarde(String produtoId) {
        agendadas.put(produtoId, Instant.now().plusSeconds(ESPERA_NOVA_TENTATIVA_S));
    }

    /** A mercadoria das ordens aceites pelo fornecedor entra no stock, de forma atomica com a mudanca de estado. */
    @Scheduled(fixedDelay = 5000)
    public void receberEntregas() {
        try {
            List<Map<String, Object>> entregas = jdbc.queryForList(
                    "WITH r AS (UPDATE ordem_compra SET estado = 'RECEBIDA' " +
                    "           WHERE estado = 'ENVIADA' AND criada_em < now() - ? * interval '1 second' " +
                    "           RETURNING produto_id, quantidade) " +
                    "UPDATE stock s SET quantidade = s.quantidade + e.total " +
                    "FROM (SELECT produto_id, SUM(quantidade)::int AS total FROM r GROUP BY produto_id) e " +
                    "WHERE s.produto_id = e.produto_id " +
                    "RETURNING s.produto_id, e.total, s.quantidade, s.limite_minimo", entregaSegundos);
            for (Map<String, Object> e : entregas) {
                String produtoId = (String) e.get("produto_id");
                int quantidade = ((Number) e.get("quantidade")).intValue();
                int minimo = ((Number) e.get("limite_minimo")).intValue();
                auditar("ENTREGA", produtoId + " +" + e.get("total") + " -> " + quantidade + " (minimo " + minimo + ")");
                if (quantidade <= minimo) agendar(produtoId);        // vendeu-se mais entretanto
            }
        } catch (Exception e) {
            log.debug("[{}] entregas por processar: {}", regiao, e.getMessage());   // ex.: mestre em baixo
        }
    }

    void auditar(String operacao, String detalhe) {
        try {
            jdbc.update("INSERT INTO auditoria (no_origem, hora, operacao, detalhe) VALUES (?, ?, ?, ?)",
                    "agente-" + regiao.toLowerCase(), java.sql.Timestamp.from(relogio.agora()), operacao, detalhe);
        } catch (Exception e) {
            log.warn("auditoria indisponivel: {}", e.getMessage());
        }
    }
}
