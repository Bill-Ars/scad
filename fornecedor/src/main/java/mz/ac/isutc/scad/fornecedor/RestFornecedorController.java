package mz.ac.isutc.scad.fornecedor;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collection;
import java.util.Map;

/**
 * Rota REST/JSON (fornecedores modernos). So existe quando MODO=REST.
 *   POST /api/ordens        -> 201 Created (nova) | 200 OK (repetida, idempotente) | 400 (invalida)
 *   GET  /api/ordens/{id}   -> 200 | 404
 */
@RestController
@RequestMapping("/api/ordens")
@ConditionalOnProperty(name = "scad.modo", havingValue = "REST")
public class RestFornecedorController {

    private final OrdemService servico;

    public RestFornecedorController(OrdemService servico) {
        this.servico = servico;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> criar(@RequestBody Map<String, Object> pedido) {
        Object q = pedido.get("quantidade");
        int quantidade = q instanceof Number n ? n.intValue() : 0;
        Object[] r = servico.receber((String) pedido.get("ordemId"), (String) pedido.get("produtoId"),
                quantidade, (String) pedido.get("regiao"));
        @SuppressWarnings("unchecked")
        Map<String, Object> corpo = (Map<String, Object>) r[0];
        boolean nova = (Boolean) r[1];
        return ResponseEntity.status(nova ? HttpStatus.CREATED : HttpStatus.OK).body(corpo);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> obter(@PathVariable String id) {
        Map<String, Object> o = servico.obter(id);
        return o == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(o);
    }

    @GetMapping
    public Collection<Map<String, Object>> listar() {
        return servico.todas().values();
    }

    @ExceptionHandler(OrdemService.OrdemInvalida.class)
    public ResponseEntity<Map<String, Object>> invalida(OrdemService.OrdemInvalida e) {
        return ResponseEntity.badRequest().body(Map.of("erro", e.getMessage()));
    }
}
