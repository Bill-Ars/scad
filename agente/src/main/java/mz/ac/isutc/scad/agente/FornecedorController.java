package mz.ac.isutc.scad.agente;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Envio manual de uma ordem a um fornecedor (para testes e demonstracao). */
@RestController
public class FornecedorController {

    private final FornecedorCliente cliente;

    public FornecedorController(FornecedorCliente cliente) {
        this.cliente = cliente;
    }

    @PostMapping("/fornecedores/enviar")
    public Map<String, Object> enviar(@RequestBody Map<String, Object> p) {
        String ordemId = p.get("ordemId") != null ? (String) p.get("ordemId") : "ORD-" + System.currentTimeMillis();
        return cliente.enviar(ordemId, (String) p.get("fornecedorId"), (String) p.get("produtoId"),
                ((Number) p.get("quantidade")).intValue());
    }
}
