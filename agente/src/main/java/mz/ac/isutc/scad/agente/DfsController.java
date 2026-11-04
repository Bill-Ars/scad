package mz.ac.isutc.scad.agente;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Faturas, relatorios de auditoria e logs de erro no HDFS, acessiveis a partir de qualquer no. */
@RestController
@RequestMapping("/dfs")
public class DfsController {

    private final HdfsCliente hdfs;
    private final Relogio relogio;
    private final String regiao;

    public DfsController(HdfsCliente hdfs, Relogio relogio, @Value("${scad.regiao}") String regiao) {
        this.hdfs = hdfs;
        this.relogio = relogio;
        this.regiao = regiao;
    }

    /** Gera a fatura PDF de uma ordem e guarda-a em /scad/faturas/<ordemId>.pdf */
    @PostMapping("/fatura")
    public Map<String, Object> fatura(@RequestBody Map<String, Object> o) {
        String caminho = guardarFatura((String) o.get("ordemId"), (String) o.get("fornecedorId"),
                (String) o.get("produtoId"), ((Number) o.get("quantidade")).intValue());
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("escritoPor", regiao);
        r.put("caminho", caminho);
        return r;
    }

    public String guardarFatura(String ordemId, String fornecedorId, String produtoId, int quantidade) {
        byte[] pdf = FaturaPdf.gerar(List.of(
                "SCAD - Fatura de compra automatica",
                "Ordem: " + ordemId,
                "Armazem: " + regiao,
                "Fornecedor: " + fornecedorId,
                "Produto: " + produtoId + "   Quantidade: " + quantidade,
                "Emitida em (hora Berkeley): " + relogio.agora(),
                "Guardada no HDFS com fator de replicacao 3"));
        String caminho = "/scad/faturas/" + ordemId + ".pdf";
        hdfs.escrever(caminho, pdf);
        return caminho;
    }

    /** Acrescenta uma linha a um log partilhado (ex: /scad/logs/erros.log) */
    @PostMapping("/log")
    public Map<String, Object> log(@RequestBody Map<String, Object> m) {
        String ficheiro = m.getOrDefault("ficheiro", "/scad/logs/erros.log").toString();
        String linha = relogio.agora() + " [" + regiao + "] " + m.get("texto") + "\n";
        hdfs.acrescentar(ficheiro, linha.getBytes(StandardCharsets.UTF_8));
        return Map.of("ficheiro", ficheiro, "linha", linha.trim());
    }

    @GetMapping("/listar")
    public List<Map<String, Object>> listar(@RequestParam(defaultValue = "/scad/faturas") String dir) {
        return hdfs.listar(dir);
    }

    /** Le um ficheiro do HDFS (o PDF abre diretamente no navegador). */
    @GetMapping("/ler")
    public ResponseEntity<byte[]> ler(@RequestParam String caminho) {
        byte[] dados = hdfs.ler(caminho);
        MediaType tipo = caminho.endsWith(".pdf") ? MediaType.APPLICATION_PDF : MediaType.TEXT_PLAIN;
        return ResponseEntity.ok().contentType(tipo).header("X-Lido-Por", regiao).body(dados);
    }
}
