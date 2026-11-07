-- Tabelas REPLICADAS integralmente em todos os nos
CREATE TABLE armazem (
  id      SERIAL PRIMARY KEY,
  nome    VARCHAR(60) NOT NULL,
  regiao  VARCHAR(20) NOT NULL UNIQUE
);
CREATE TABLE produto (
  id       VARCHAR(10) PRIMARY KEY,
  nome     VARCHAR(80) NOT NULL,
  unidade  VARCHAR(20) NOT NULL,
  preco    NUMERIC(12,2) NOT NULL DEFAULT 0 CHECK (preco >= 0)   -- MZN por unidade
);
CREATE TABLE fornecedor (
  id               VARCHAR(10) PRIMARY KEY,
  nome             VARCHAR(80) NOT NULL,
  tipo_integracao  VARCHAR(4)  NOT NULL CHECK (tipo_integracao IN ('REST','SOAP')),
  endpoint         VARCHAR(200) NOT NULL
);
INSERT INTO armazem (nome, regiao) VALUES
  ('Armazem Sul','Maputo'), ('Armazem Centro','Beira'), ('Armazem Norte','Nampula');
-- Sem produtos: sao registados no sistema (ou com zerar.ps1 -Demo)
INSERT INTO fornecedor VALUES
  ('F1','Distribuidora Moderna','REST','http://fornecedor-rest:8080/api/ordens'),
  ('F2','Grossista Legado','SOAP','http://fornecedor-soap:8080/ws');
