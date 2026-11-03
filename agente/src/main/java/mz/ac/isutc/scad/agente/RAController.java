package mz.ac.isutc.scad.agente;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Mensagens do Ricart-Agrawala entre nos e endpoint de demonstracao. */
@RestController
@RequestMapping("/ra")
public class RAController {

    private final RicartAgrawala ra;
    private final Relogio relogio;
    private final String regiao;

    public RAController(RicartAgrawala ra, Relogio relogio, @Value("${scad.regiao}") String regiao) {
        this.ra = ra;
        this.relogio = relogio;
        this.regiao = regiao;
    }

    @PostMapping("/request")
    public Map<String, Object> request(@RequestBody Map<String, Object> m) {
        boolean concedido = ra.receberRequest((String) m.get("recurso"),
                ((Number) m.get("ts")).longValue(), ((Number) m.get("id")).intValue(), (String) m.get("origem"));
        return Map.of("concedido", concedido);
    }

    @PostMapping("/reply")
    public Map<String, Object> reply(@RequestBody Map<String, Object> m) {
        ra.receberReply((String) m.get("recurso"), (String) m.get("origem"));
        return Map.of("ok", true);
    }

    /** Demonstracao: pede o lote, "compra-o" durante N segundos dentro da seccao critica e sai. */
    @PostMapping("/demo")
    public Map<String, Object> demo(@RequestParam(defaultValue = "F1:P2") String recurso,
                                    @RequestParam(defaultValue = "5") int segundos) throws InterruptedException {
        Instant pedido = relogio.agora();
        Map<String, Object> info = ra.entrar(recurso);
        Instant entrou = relogio.agora();
        Thread.sleep(segundos * 1000L);                 // simula a compra do lote dentro da seccao critica
        Instant saiu = relogio.agora();
        List<String> adiados = ra.sair(recurso);

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("regiao", regiao);
        r.put("recurso", recurso);
        r.put("pedidoEm", pedido.toString());
        r.put("entrouEm", entrou.toString());
        r.put("saiuEm", saiu.toString());
        r.put("esperouMs", Duration.between(pedido, entrou).toMillis());
        r.putAll(info);
        r.put("replyAdiadoEnviadoA", adiados);
        return r;
    }
}
