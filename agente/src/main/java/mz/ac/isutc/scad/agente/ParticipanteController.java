package mz.ac.isutc.scad.agente;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** PARTICIPANTE do 2PC: prepara, confirma ou aborta a sua parte na base de dados local. */
@RestController
@RequestMapping("/tx")
public class ParticipanteController {

    // O PREPARE TRANSACTION nao aceita parametros, por isso o gid e validado (evita SQL injection)
    private static final Pattern GID_VALIDO = Pattern.compile("[A-Za-z0-9_-]{1,100}");

    private final DataSource dataSource;
    private final JdbcTemplate jdbc;
    private final String regiao;

    public ParticipanteController(DataSource dataSource, JdbcTemplate jdbc,
                                  @Value("${scad.regiao}") String regiao) {
        this.dataSource = dataSource;
        this.jdbc = jdbc;
        this.regiao = regiao;
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
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO ordem_compra (id, produto_id, fornecedor_id, regiao, quantidade, estado) " +
                    "VALUES (?, ?, ?, ?, ?, 'CONFIRMADA')")) {
                ps.setString(1, pedido.get("ordemId") + "-" + regiao.toUpperCase());
                ps.setString(2, (String) pedido.get("produtoId"));
                ps.setString(3, (String) pedido.get("fornecedorId"));
                ps.setString(4, regiao);
                ps.setInt(5, ((Number) pedido.get("quantidade")).intValue());
                ps.executeUpdate();
                try (Statement s = c.createStatement()) {
                    s.execute("PREPARE TRANSACTION '" + gid + "'");    // voto SIM: fica gravado em disco
                }
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

    /** Transacoes preparadas que ainda esperam pela decisao do coordenador. */
    @GetMapping("/pendentes")
    public List<Map<String, Object>> pendentes() {
        return jdbc.queryForList("SELECT gid, prepared FROM pg_prepared_xacts ORDER BY prepared");
    }
}
