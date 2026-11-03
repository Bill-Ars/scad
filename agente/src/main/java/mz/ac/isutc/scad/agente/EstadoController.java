package mz.ac.isutc.scad.agente;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Endpoints internos do no: o seu estado e o que ele sabe dos outros. */
@RestController
public class EstadoController {

    private final JdbcTemplate jdbc;
    private final MonitorPares monitor;
    private final String regiao;

    public EstadoController(JdbcTemplate jdbc, MonitorPares monitor,
                            @Value("${scad.regiao}") String regiao) {
        this.jdbc = jdbc;
        this.monitor = monitor;
        this.regiao = regiao;
    }

    @GetMapping("/estado")
    public Map<String, Object> estado() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("regiao", regiao);
        r.put("hora", Instant.now().toString());
        try {
            Map<String, Object> bd = jdbc.queryForMap(
                "SELECT current_setting('cluster_name') AS servidor, " +
                "       pg_is_in_recovery() AS em_recuperacao");
            boolean replica = Boolean.TRUE.equals(bd.get("em_recuperacao"));
            r.put("baseDeDados", "OK");
            r.put("servidorBD", bd.get("servidor"));
            r.put("modo", replica ? "REPLICA (so leitura)" : "MESTRE (leitura e escrita)");
        } catch (Exception e) {
            r.put("baseDeDados", "FALHA: " + e.getMessage());
        }
        return r;
    }

    @GetMapping("/pares")
    public Map<String, String> pares() {
        return monitor.getEstadoPares();
    }
}
