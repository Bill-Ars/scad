package mz.ac.isutc.scad.agente;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Integracao com fornecedores externos: escolhe a rota pelo tipo do fornecedor (REST/JSON ou SOAP/XML)
 * e envia a ordem com timeout e novas tentativas (1 s, 2 s, 4 s). O ordemId torna o envio idempotente.
 */
@Component
public class FornecedorCliente {

    private static final Logger log = LoggerFactory.getLogger(FornecedorCliente.class);
    private static final String NS = "http://scad.isutc.ac.mz/fornecedor";
    private static final int TENTATIVAS = 3;

    private final JdbcTemplate jdbc;
    private final Relogio relogio;
    private final String regiao;
    private final RestClient http;

    public FornecedorCliente(JdbcTemplate jdbc, Relogio relogio, @Value("${scad.regiao}") String regiao) {
        this.jdbc = jdbc;
        this.relogio = relogio;
        this.regiao = regiao;
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(Duration.ofSeconds(2));
        f.setReadTimeout(Duration.ofSeconds(3));
        this.http = RestClient.builder().requestFactory(f).build();
    }

    public Map<String, Object> enviar(String ordemId, String fornecedorId, String produtoId, int quantidade) {
        Map<String, Object> forn = jdbc.queryForMap(
                "SELECT id, nome, tipo_integracao, endpoint FROM fornecedor WHERE id = ?", fornecedorId);
        String tipo = (String) forn.get("tipo_integracao");
        String endpoint = (String) forn.get("endpoint");

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ordemId", ordemId);
        r.put("fornecedor", forn.get("nome"));
        r.put("rota", tipo);
        r.put("endpoint", endpoint);

        String pedido = "SOAP".equals(tipo) ? envelopeSoap(ordemId, produtoId, quantidade)
                                             : jsonRest(ordemId, produtoId, quantidade);
        r.put("pedidoEnviado", pedido);

        List<String> tentativas = new ArrayList<>();
        long espera = 1000;
        for (int i = 1; i <= TENTATIVAS; i++) {
            try {
                Map<String, Object> resp = "SOAP".equals(tipo) ? chamarSoap(endpoint, pedido)
                                                                : chamarRest(endpoint, ordemId, produtoId, quantidade);
                tentativas.add(i + ": OK");
                r.put("tentativas", tentativas);
                r.put("estado", "ENTREGUE");
                r.put("resposta", resp);
                auditar("ORDEM-ENVIADA", ordemId + " " + tipo + " " + fornecedorId + " -> " + resp.get("estado"));
                return r;
            } catch (HttpClientErrorException e) {                 // 4xx: pedido invalido, nao adianta repetir
                tentativas.add(i + ": REJEITADA " + e.getStatusCode() + " " + e.getResponseBodyAsString());
                r.put("tentativas", tentativas);
                r.put("estado", "REJEITADA");
                auditar("ORDEM-REJEITADA", ordemId + " " + tipo + " " + e.getStatusCode());
                return r;
            } catch (SoapFault e) {                                 // soap:Client -> erro do pedido
                tentativas.add(i + ": SOAP FAULT " + e.getMessage());
                r.put("tentativas", tentativas);
                r.put("estado", "REJEITADA");
                auditar("ORDEM-REJEITADA", ordemId + " SOAP " + e.getMessage());
                return r;
            } catch (Exception e) {                                 // timeout, ligacao recusada, 5xx
                tentativas.add(i + ": FALHOU (" + e.getClass().getSimpleName() + ")");
                log.warn("[Fornecedor] tentativa {} para {} falhou: {}", i, endpoint, e.getMessage());
                if (i < TENTATIVAS) {
                    try { Thread.sleep(espera); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                    espera *= 2;                                    // espera crescente
                }
            }
        }
        r.put("tentativas", tentativas);
        r.put("estado", "FALHOU - fornecedor indisponivel, repetir mais tarde");
        auditar("ERRO-FORNECEDOR", ordemId + " " + tipo + " indisponivel apos " + TENTATIVAS + " tentativas");
        return r;
    }

    // ---------------- REST / JSON ----------------
    private String jsonRest(String ordemId, String produtoId, int quantidade) {
        return "{\"ordemId\":\"" + ordemId + "\",\"produtoId\":\"" + produtoId +
               "\",\"quantidade\":" + quantidade + ",\"regiao\":\"" + regiao + "\"}";
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> chamarRest(String url, String ordemId, String produtoId, int quantidade) {
        Map<String, Object> corpo = new LinkedHashMap<>();
        corpo.put("ordemId", ordemId);
        corpo.put("produtoId", produtoId);
        corpo.put("quantidade", quantidade);
        corpo.put("regiao", regiao);
        var resp = http.post().uri(url).contentType(MediaType.APPLICATION_JSON).body(corpo)
                .retrieve().toEntity(Map.class);
        Map<String, Object> m = new LinkedHashMap<>((Map<String, Object>) resp.getBody());
        m.put("httpStatus", resp.getStatusCode().value());
        return m;
    }

    // ---------------- SOAP / XML ----------------
    private String envelopeSoap(String ordemId, String produtoId, int quantidade) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
               "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\" xmlns:f=\"" + NS + "\">" +
               "<soap:Header/><soap:Body><f:EnviarOrdemRequest>" +
               "<f:ordemId>" + ordemId + "</f:ordemId>" +
               "<f:produtoId>" + produtoId + "</f:produtoId>" +
               "<f:quantidade>" + quantidade + "</f:quantidade>" +
               "<f:regiao>" + regiao + "</f:regiao>" +
               "</f:EnviarOrdemRequest></soap:Body></soap:Envelope>";
    }

    static class SoapFault extends RuntimeException {
        SoapFault(String m) { super(m); }
    }

    private Map<String, Object> chamarSoap(String url, String envelope) throws Exception {
        String xml;
        try {
            xml = http.post().uri(url)
                    .contentType(MediaType.parseMediaType("text/xml;charset=UTF-8"))
                    .header("SOAPAction", NS + "/EnviarOrdem")
                    .body(envelope).retrieve().body(String.class);
        } catch (HttpServerErrorException e) {
            xml = e.getResponseBodyAsString();                     // SOAP 1.1: Fault vem com HTTP 500
            if (!xml.contains("Fault")) throw e;
        }
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        Document d = f.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        NodeList fault = d.getElementsByTagNameNS("http://schemas.xmlsoap.org/soap/envelope/", "Fault");
        if (fault.getLength() > 0) {
            String fs = d.getElementsByTagName("faultstring").item(0).getTextContent();
            String fc = d.getElementsByTagName("faultcode").item(0).getTextContent();
            if (fc.contains("Client")) throw new SoapFault(fs);
            throw new IllegalStateException("SOAP Server fault: " + fs);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        for (String campo : new String[]{"ordemId", "estado", "fornecedor", "previsaoEntrega", "repetida"}) {
            NodeList l = d.getElementsByTagNameNS(NS, campo);
            if (l.getLength() > 0) m.put(campo, l.item(0).getTextContent());
        }
        m.put("respostaXml", xml);
        return m;
    }

    private void auditar(String operacao, String detalhe) {
        try {
            jdbc.update("INSERT INTO auditoria (no_origem, hora, operacao, detalhe) VALUES (?, ?, ?, ?)",
                    "agente-" + regiao.toLowerCase(), java.sql.Timestamp.from(relogio.agora()), operacao, detalhe);
        } catch (Exception e) {
            log.warn("auditoria indisponivel: {}", e.getMessage());
        }
    }
}
