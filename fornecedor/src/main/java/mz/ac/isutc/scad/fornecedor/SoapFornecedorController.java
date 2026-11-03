package mz.ac.isutc.scad.fornecedor;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Rota SOAP/XML (sistemas empresariais legados). So existe quando MODO=SOAP.
 *   GET  /ws?wsdl  -> contrato WSDL
 *   POST /ws       -> envelope SOAP 1.1 com EnviarOrdemRequest; resposta EnviarOrdemResponse ou soap:Fault
 */
@RestController
@ConditionalOnProperty(name = "scad.modo", havingValue = "SOAP")
public class SoapFornecedorController {

    static final String NS = "http://scad.isutc.ac.mz/fornecedor";
    static final String SOAP_NS = "http://schemas.xmlsoap.org/soap/envelope/";
    private static final MediaType XML = MediaType.parseMediaType("text/xml;charset=UTF-8");

    private final OrdemService servico;

    public SoapFornecedorController(OrdemService servico) {
        this.servico = servico;
    }

    @GetMapping(value = "/ws", params = "wsdl")
    public ResponseEntity<String> wsdl() throws Exception {
        String w = new String(new ClassPathResource("fornecedor.wsdl").getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        return ResponseEntity.ok().contentType(XML).body(w);
    }

    @PostMapping(value = "/ws")
    public ResponseEntity<String> enviarOrdem(@RequestBody String envelope) {
        try {
            Document doc = ler(envelope);
            if (doc.getElementsByTagNameNS(NS, "EnviarOrdemRequest").getLength() == 0) {
                return fault("soap:Client", "Operacao desconhecida: esperado EnviarOrdemRequest");
            }
            String ordemId = texto(doc, "ordemId");
            String produtoId = texto(doc, "produtoId");
            String regiao = texto(doc, "regiao");
            int quantidade;
            try {
                quantidade = Integer.parseInt(texto(doc, "quantidade").trim());
            } catch (Exception e) {
                return fault("soap:Client", "quantidade invalida");
            }
            Object[] r = servico.receber(ordemId, produtoId, quantidade, regiao);
            @SuppressWarnings("unchecked")
            Map<String, Object> o = (Map<String, Object>) r[0];
            String corpo =
                "<f:EnviarOrdemResponse xmlns:f=\"" + NS + "\">" +
                "<f:ordemId>" + esc(o.get("ordemId")) + "</f:ordemId>" +
                "<f:estado>" + esc(o.get("estado")) + "</f:estado>" +
                "<f:fornecedor>" + esc(o.get("fornecedor")) + "</f:fornecedor>" +
                "<f:previsaoEntrega>" + esc(o.get("previsaoEntrega")) + "</f:previsaoEntrega>" +
                "<f:repetida>" + !((Boolean) r[1]) + "</f:repetida>" +
                "</f:EnviarOrdemResponse>";
            return ResponseEntity.ok().contentType(XML).body(envolver(corpo));
        } catch (OrdemService.OrdemInvalida e) {
            return fault("soap:Client", e.getMessage());
        } catch (Exception e) {
            return fault("soap:Client", "XML mal formado: " + e.getMessage());
        }
    }

    private static Document ler(String xml) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);   // protege contra XXE
        return f.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    private static String texto(Document d, String elemento) {
        NodeList l = d.getElementsByTagNameNS(NS, elemento);
        return l.getLength() == 0 ? null : l.item(0).getTextContent();
    }

    private static String esc(Object o) {
        return String.valueOf(o).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String envolver(String corpo) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
               "<soap:Envelope xmlns:soap=\"" + SOAP_NS + "\"><soap:Header/><soap:Body>" +
               corpo + "</soap:Body></soap:Envelope>";
    }

    /** SOAP 1.1: os erros voltam como soap:Fault com HTTP 500. */
    private static ResponseEntity<String> fault(String codigo, String mensagem) {
        String f = "<soap:Fault><faultcode>" + codigo + "</faultcode><faultstring>" + esc(mensagem) +
                   "</faultstring></soap:Fault>";
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).contentType(XML).body(envolver(f));
    }
}
