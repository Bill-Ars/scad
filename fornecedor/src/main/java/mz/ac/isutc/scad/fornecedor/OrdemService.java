package mz.ac.isutc.scad.fornecedor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Logica de negocio do fornecedor: aceita ordens de forma IDEMPOTENTE (o mesmo ordemId nunca duplica). */
@Service
public class OrdemService {

    public static class OrdemInvalida extends RuntimeException {
        public OrdemInvalida(String m) { super(m); }
    }

    private final String nome;
    private final Map<String, Map<String, Object>> ordens = new ConcurrentHashMap<>();

    public OrdemService(@Value("${scad.nome}") String nome) {
        this.nome = nome;
    }

    /** Devolve [resposta, nova?]. Se o ordemId ja existir, devolve a resposta original (idempotencia). */
    public synchronized Object[] receber(String ordemId, String produtoId, int quantidade, String regiao) {
        if (ordemId == null || ordemId.isBlank()) throw new OrdemInvalida("ordemId obrigatorio");
        if (produtoId == null || produtoId.isBlank()) throw new OrdemInvalida("produtoId obrigatorio");
        if (quantidade <= 0) throw new OrdemInvalida("quantidade tem de ser positiva");
        Map<String, Object> existente = ordens.get(ordemId);
        if (existente != null) return new Object[]{existente, false};

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ordemId", ordemId);
        r.put("estado", "ACEITE");
        r.put("fornecedor", nome);
        r.put("produtoId", produtoId);
        r.put("quantidade", quantidade);
        r.put("regiao", regiao);
        r.put("previsaoEntrega", LocalDate.now().plusDays(5).toString());
        r.put("recebidaEm", Instant.now().toString());
        ordens.put(ordemId, r);
        return new Object[]{r, true};
    }

    public Map<String, Object> obter(String ordemId) {
        return ordens.get(ordemId);
    }

    public Map<String, Map<String, Object>> todas() {
        return ordens;
    }
}
