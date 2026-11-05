package mz.ac.isutc.scad.agente;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Catalogo de produtos. O produto esta replicado nos 3 nos e tem stock em cada fragmento, por isso
 * registar, editar ou apagar passa por 2PC: fica feito nos 3 armazens ou em nenhum.
 */
@RestController
@RequestMapping("/painel/produtos")
public class ProdutoController {

    private static final List<String> REGIOES = List.of("Maputo", "Beira", "Nampula");
    private static final int MAXIMO = 100_000;

    private final CoordenadorController coordenador;
    private final MotorCompras motor;
    private final JdbcTemplate jdbc;

    public ProdutoController(CoordenadorController coordenador, MotorCompras motor, JdbcTemplate jdbc) {
        this.coordenador = coordenador;
        this.motor = motor;
        this.jdbc = jdbc;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> criar(@RequestBody Map<String, Object> pedido) {
        String invalido = validar(pedido, true);
        if (invalido != null) return erro(HttpStatus.BAD_REQUEST, invalido);
        String id = "P" + jdbc.queryForObject(
                "SELECT COALESCE(MAX(substring(id FROM 2)::int), 0) + 1 FROM produto WHERE id ~ '^P[0-9]+$'", Integer.class);
        return executar("PRODUTO_CRIAR", id, pedido, "PRODUTO-CRIADO", id + " " + texto(pedido, "nome"));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> editar(@PathVariable String id, @RequestBody Map<String, Object> pedido) {
        String invalido = validar(pedido, false);
        if (invalido != null) return erro(HttpStatus.BAD_REQUEST, invalido);
        return executar("PRODUTO_EDITAR", id, pedido, "PRODUTO-EDITADO", id + " " + texto(pedido, "nome"));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> apagar(@PathVariable String id) {
        return executar("PRODUTO_APAGAR", id, Map.of(), "PRODUTO-APAGADO", id);
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<Map<String, Object>> executar(String tipo, String id, Map<String, Object> pedido,
                                                         String operacao, String detalhe) {
        Map<String, Map<String, Object>> partes = new LinkedHashMap<>();
        Map<String, Object> regioes = (Map<String, Object>) pedido.getOrDefault("regioes", Map.of());
        for (String regiao : REGIOES) {
            Map<String, Object> parte = new LinkedHashMap<>(pedido);
            parte.remove("regioes");
            parte.put("tipo", tipo);
            parte.put("id", id);
            parte.putAll((Map<String, Object>) regioes.getOrDefault(regiao, Map.of()));
            partes.put(regiao, parte);
        }
        Map<String, Object> r = coordenador.executar2pc("produto-" + System.currentTimeMillis(), partes, false);
        r.put("id", id);
        if (!"COMMIT".equals(r.get("decisao"))) {
            r.put("erro", "Nada foi alterado em nenhum armazem: " + r.get("fase1_votos"));
            return ResponseEntity.status(HttpStatus.CONFLICT).body(r);
        }
        motor.auditar(operacao, detalhe);
        return ResponseEntity.ok(r);
    }

    /** Devolve o motivo da recusa, ou null se o pedido for valido. */
    @SuppressWarnings("unchecked")
    private String validar(Map<String, Object> p, boolean novo) {
        if (texto(p, "nome").isEmpty() || texto(p, "nome").length() > 80) return "o nome e obrigatorio (ate 80 caracteres)";
        if (texto(p, "unidade").isEmpty() || texto(p, "unidade").length() > 20) return "a unidade e obrigatoria (ate 20 caracteres)";
        Integer fornecedor = jdbc.queryForObject("SELECT count(*) FROM fornecedor WHERE id = ?", Integer.class, texto(p, "fornecedorId"));
        if (fornecedor == null || fornecedor == 0) return "fornecedor invalido";
        if (!(p.get("regioes") instanceof Map)) return "faltam os dados dos armazens";
        Map<String, Object> regioes = (Map<String, Object>) p.get("regioes");
        for (String regiao : REGIOES) {
            if (!(regioes.get(regiao) instanceof Map)) return "faltam os dados de " + regiao;
            Map<String, Object> dados = (Map<String, Object>) regioes.get(regiao);
            if (!inteiroEntre(dados.get("limiteMinimo"), 1, MAXIMO)) return "o minimo de " + regiao + " tem de estar entre 1 e " + MAXIMO;
            if (novo && !inteiroEntre(dados.get("quantidade"), 0, MAXIMO)) return "o stock de " + regiao + " tem de estar entre 0 e " + MAXIMO;
        }
        return null;
    }

    private static boolean inteiroEntre(Object valor, int minimo, int maximo) {
        return valor instanceof Integer i && i >= minimo && i <= maximo;
    }

    private static String texto(Map<String, Object> p, String campo) {
        return p.get(campo) instanceof String s ? s.trim() : "";
    }

    private static ResponseEntity<Map<String, Object>> erro(HttpStatus estado, String mensagem) {
        return ResponseEntity.status(estado).body(Map.of("erro", mensagem));
    }
}
