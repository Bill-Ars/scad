package mz.ac.isutc.scad.agente;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Cria as tabelas dos clientes, tambem em bases ja existentes (sem obrigar a recomecar do zero).
 * cliente: replicada nos 3 nos (como o catalogo), escrita por 2PC.
 * pedido_cliente e pagamento: fragmentadas por regiao (como o stock), cada no so guarda os do seu armazem.
 * produto.preco: preco por unidade, replicado com o resto do catalogo.
 */
@Component
public class EsquemaClientes implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(EsquemaClientes.class);

    private final JdbcTemplate jdbc;
    private final String regiao;

    public EsquemaClientes(JdbcTemplate jdbc, @Value("${scad.regiao}") String regiao) {
        this.jdbc = jdbc;
        this.regiao = regiao;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            jdbc.execute("CREATE TABLE IF NOT EXISTS cliente (" +
                    " id VARCHAR(24) PRIMARY KEY," +
                    " nome VARCHAR(60) NOT NULL," +
                    " tipo VARCHAR(20) NOT NULL," +
                    " email VARCHAR(120) NOT NULL UNIQUE," +
                    " senha_hash VARCHAR(200) NOT NULL," +
                    " criado_em TIMESTAMPTZ NOT NULL DEFAULT now())");
            jdbc.execute("CREATE TABLE IF NOT EXISTS pedido_cliente (" +
                    " id BIGSERIAL PRIMARY KEY," +
                    " pedido VARCHAR(40) NOT NULL," +
                    " cliente_id VARCHAR(24) NOT NULL REFERENCES cliente(id)," +
                    " regiao VARCHAR(20) NOT NULL CHECK (regiao = '" + regiao.replace("'", "") + "')," +
                    " produto_id VARCHAR(10) NOT NULL REFERENCES produto(id)," +
                    " quantidade INT NOT NULL CHECK (quantidade > 0)," +
                    " criado_em TIMESTAMPTZ NOT NULL)");
            jdbc.execute("ALTER TABLE produto ADD COLUMN IF NOT EXISTS preco NUMERIC(12,2) NOT NULL DEFAULT 0 CHECK (preco >= 0)");
            jdbc.execute("ALTER TABLE pedido_cliente ADD COLUMN IF NOT EXISTS preco_unitario NUMERIC(12,2) NOT NULL DEFAULT 0");
            jdbc.execute("CREATE TABLE IF NOT EXISTS pagamento (" +
                    " pedido VARCHAR(40) PRIMARY KEY," +
                    " cliente_id VARCHAR(24) NOT NULL REFERENCES cliente(id)," +
                    " regiao VARCHAR(20) NOT NULL CHECK (regiao = '" + regiao.replace("'", "") + "')," +
                    " metodo VARCHAR(20) NOT NULL," +
                    " conta VARCHAR(20)," +
                    " referencia VARCHAR(40) NOT NULL," +
                    " valor NUMERIC(14,2) NOT NULL CHECK (valor >= 0)," +
                    " estado VARCHAR(12) NOT NULL," +
                    " criado_em TIMESTAMPTZ NOT NULL)");
        } catch (Exception e) {
            log.warn("[{}] tabelas de clientes nao criadas (base em so leitura?): {}", regiao, e.getMessage());
        }
    }
}
