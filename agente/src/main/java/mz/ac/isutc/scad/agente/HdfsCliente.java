package mz.ac.isutc.scad.agente;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Cliente do HDFS via WebHDFS (API REST do Hadoop).
 * Escrita em 2 passos: o NameNode indica o DataNode (Location) e os dados vao diretamente para esse DataNode,
 * que os replica em pipeline pelos outros DataNodes (fator de replicacao 3).
 */
@Component
public class HdfsCliente {

    private final String base;
    private final RestClient http;

    public HdfsCliente(@Value("${scad.hdfs-url:http://namenode:9870}") String base) {
        this.base = base;
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(Duration.ofSeconds(3));
        f.setReadTimeout(Duration.ofSeconds(15));
        this.http = RestClient.builder().requestFactory(f).build();
    }

    private String url(String caminho, String op, String extra) {
        return base + "/webhdfs/v1" + caminho + "?op=" + op + "&user.name=hadoop" + extra;
    }

    /** Passo 1: pede ao NameNode o endereco do DataNode (noredirect devolve o Location em JSON). */
    private String localizar(HttpMethod metodo, String caminho, String op, String extra) {
        Map<?, ?> r = http.method(metodo).uri(URI.create(url(caminho, op, "&noredirect=true" + extra)))
                .retrieve().body(Map.class);
        return (String) r.get("Location");
    }

    public void escrever(String caminho, byte[] dados) {
        String dataNode = localizar(HttpMethod.PUT, caminho, "CREATE", "&overwrite=true");
        http.put().uri(URI.create(dataNode)).contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(dados).retrieve().toBodilessEntity();
    }

    public void acrescentar(String caminho, byte[] dados) {
        if (!existe(caminho)) {
            escrever(caminho, dados);
            return;
        }
        String dataNode = localizar(HttpMethod.POST, caminho, "APPEND", "");
        http.post().uri(URI.create(dataNode)).contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(dados).retrieve().toBodilessEntity();
    }

    public byte[] ler(String caminho) {
        String dataNode = localizar(HttpMethod.GET, caminho, "OPEN", "");
        return http.get().uri(URI.create(dataNode)).retrieve().body(byte[].class);
    }

    public boolean existe(String caminho) {
        try {
            http.get().uri(URI.create(url(caminho, "GETFILESTATUS", ""))).retrieve().toBodilessEntity();
            return true;
        } catch (HttpClientErrorException.NotFound e) {
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> listar(String dir) {
        Map<String, Object> r = http.get().uri(URI.create(url(dir, "LISTSTATUS", ""))).retrieve().body(Map.class);
        Map<String, Object> fs = (Map<String, Object>) r.get("FileStatuses");
        return (List<Map<String, Object>>) fs.get("FileStatus");
    }
}
