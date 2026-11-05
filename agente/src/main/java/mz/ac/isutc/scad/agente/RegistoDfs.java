package mz.ac.isutc.scad.agente;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Envia para o HDFS os logs de erros e os relatorios de auditoria deste no.
 * Cada no escreve os seus ficheiros, porque o HDFS so admite um escritor de cada vez por ficheiro.
 * Os erros passam por uma fila: o caminho critico nao espera pelo HDFS e, se ele estiver em baixo, nada se perde.
 */
@Component
public class RegistoDfs {

    private static final Logger log = LoggerFactory.getLogger(RegistoDfs.class);
    private static final int MAX_PENDENTES = 1000;

    private final HdfsCliente hdfs;
    private final JdbcTemplate jdbc;
    private final Relogio relogio;
    private final String regiao;
    private final Queue<String> pendentes = new ConcurrentLinkedQueue<>();
    private final Map<String, byte[]> ficheirosPendentes = new ConcurrentHashMap<>();
    private volatile long ultimoIdExportado = 0;

    public RegistoDfs(HdfsCliente hdfs, JdbcTemplate jdbc, Relogio relogio, @Value("${scad.regiao}") String regiao) {
        this.hdfs = hdfs;
        this.jdbc = jdbc;
        this.relogio = relogio;
        this.regiao = regiao;
    }

    public void erro(String texto) {
        pendentes.add(relogio.agora() + " [" + regiao + "] " + texto.replace('\n', ' ') + "\n");
        if (pendentes.size() > MAX_PENDENTES) pendentes.poll();
    }

    /** Guarda um ficheiro no HDFS; se falhar, fica em fila ate conseguir. Devolve true se ficou ja guardado. */
    public boolean guardarComRepeticao(String caminho, byte[] dados) {
        try {
            hdfs.escrever(caminho, dados);
            return true;
        } catch (Exception e) {
            ficheirosPendentes.put(caminho, dados);
            erro("ficheiro " + caminho + " fica em fila: " + e.getClass().getSimpleName());
            return false;
        }
    }

    @Scheduled(fixedDelay = 5000)
    public void enviarFicheirosPendentes() {
        for (Map.Entry<String, byte[]> f : ficheirosPendentes.entrySet()) {
            try {
                hdfs.escrever(f.getKey(), f.getValue());
                ficheirosPendentes.remove(f.getKey());
                log.info("{} guardado no HDFS depois de esperar", f.getKey());
            } catch (Exception e) {
                return;         // HDFS ainda em baixo: nao vale a pena tentar os restantes agora
            }
        }
    }

    @Scheduled(fixedDelay = 5000)
    public void enviarErros() {
        List<String> lote = new ArrayList<>();
        for (String linha; (linha = pendentes.poll()) != null; ) lote.add(linha);
        if (lote.isEmpty()) return;
        try {
            hdfs.acrescentar("/scad/logs/erros-" + regiao.toLowerCase() + ".log",
                    String.join("", lote).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            pendentes.addAll(lote);
            log.warn("HDFS indisponivel: {} linhas de erro ficam a espera", lote.size());
        }
    }

    /** Relatorio diario em CSV. So reescreve os dias com registos novos, por isso repetir nao duplica nada. */
    @Scheduled(initialDelay = 30000, fixedDelay = 60000)
    public void exportarAgendado() {
        try {
            exportarAuditoria();
        } catch (Exception e) {
            log.warn("relatorio de auditoria nao exportado: {}", e.getMessage());
        }
    }

    public List<String> exportarAuditoria() {
        long maximo = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) FROM auditoria", Long.class);
        List<String> escritos = new ArrayList<>();
        if (maximo <= ultimoIdExportado) return escritos;
        List<String> dias = jdbc.queryForList(
                "SELECT DISTINCT to_char(hora AT TIME ZONE 'UTC', 'YYYY-MM-DD') FROM auditoria WHERE id > ?",
                String.class, ultimoIdExportado);
        for (String dia : dias) {
            String caminho = "/scad/auditoria/auditoria-" + regiao.toLowerCase() + "-" + dia + ".csv";
            hdfs.escrever(caminho, relatorioDoDia(dia).getBytes(StandardCharsets.UTF_8));
            escritos.add(caminho);
        }
        ultimoIdExportado = maximo;
        return escritos;
    }

    private String relatorioDoDia(String dia) {
        StringBuilder csv = new StringBuilder("hora_utc;no;operacao;detalhe\n");
        List<Map<String, Object>> linhas = jdbc.queryForList(
                "SELECT to_char(hora AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS.MS') AS hora, no_origem, operacao, detalhe " +
                "FROM auditoria WHERE to_char(hora AT TIME ZONE 'UTC', 'YYYY-MM-DD') = ? ORDER BY id", dia);
        for (Map<String, Object> l : linhas) {
            csv.append(l.get("hora")).append(';').append(l.get("no_origem")).append(';').append(l.get("operacao"))
               .append(";\"").append(String.valueOf(l.get("detalhe")).replace("\"", "\"\"")).append("\"\n");
        }
        return csv.toString();
    }
}
